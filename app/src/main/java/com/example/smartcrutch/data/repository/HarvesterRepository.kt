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
     * Poll for new data from an instrument.
     */
    suspend fun pollData(instrumentId: String, since: Long? = null): List<InstrumentData>? {
        return try {
            val response = apiService.pollData(PollingRequest(instrumentId, since))
            if (response.isSuccessful) {
                response.body()?.data
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
    suspend fun sendData(instrumentId: String, payload: String): Boolean {
        return try {
            val response = apiService.sendData(SendDataRequest(instrumentId, payload))
            response.isSuccessful
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Creates a live feed for an instrument by polling at regular intervals.
     * 
     * @param instrumentId The ID of the instrument to monitor.
     * @param intervalMillis The polling interval in milliseconds.
     */
    fun getLiveFeed(instrumentId: String, intervalMillis: Long = 5000): Flow<List<InstrumentData>> = flow {
        var lastTimestamp: Long? = null
        
        while (true) {
            val newData = pollData(instrumentId, lastTimestamp)
            if (newData != null && newData.isNotEmpty()) {
                emit(newData)
                // Update lastTimestamp to the most recent data point
                lastTimestamp = newData.maxByOrNull { it.timestamp }?.timestamp
            }
            delay(intervalMillis)
        }
    }
}
