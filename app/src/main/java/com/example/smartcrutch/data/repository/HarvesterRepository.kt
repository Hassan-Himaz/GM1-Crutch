package com.example.smartcrutch.data.repository

import com.example.smartcrutch.data.model.*
import com.example.smartcrutch.data.remote.HarvesterClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

class HarvesterRepository {

    private val apiService = HarvesterClient.apiService

    /**
     * Poll for new data from an instrument using cursor (DeviceDataId).
     */
    suspend fun pollData(instrumentId: String, cursor: Long? = null): PollingResponse? {
        return try {
            val response = apiService.pollData(instrumentId, cursor)
            if (response.isSuccessful) {
                response.body()
            } else {
                null
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Send data to an instrument.
     */
    suspend fun sendData(instrumentId: String, data: String): Boolean {
        return try {
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault()).format(java.util.Date())
            val response = apiService.sendData(instrumentId, SendDataRequest(data, timestamp))
            response.isSuccessful
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Creates a live feed for an instrument by polling at regular intervals.
     * Uses nextCursor for reliable sequencing as per Guide Section 8.
     */
    fun getLiveFeed(instrumentId: String, intervalMillis: Long = 5000): Flow<List<InstrumentData>> = flow {
        var currentCursor: Long? = null
        
        while (true) {
            val result = pollData(instrumentId, currentCursor)
            if (result != null) {
                // To avoid getting the same data over and over, we only emit 
                // data newer than our current cursor.
                val newData = result.data.filter { currentCursor == null || it.deviceDataId > currentCursor!! }
                
                if (newData.isNotEmpty()) {
                    emit(newData)
                    // Update cursor to the NEWEST ID in this batch
                    currentCursor = newData.maxOf { it.deviceDataId }
                } else {
                    // No new data, but update cursor to the one provided by server
                    // in case there was a skip or gap.
                    currentCursor = result.nextCursor
                }
            }
            delay(intervalMillis)
        }
    }
}
