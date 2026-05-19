package com.example.smartcrutch.data.remote

import com.example.smartcrutch.data.model.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface HarvesterApiService {

    /**
     * Poll for data from a specific instrument.
     * Use 'since' timestamp to get only new data.
     */
    @POST("harvester/poll")
    suspend fun pollData(@Body request: PollingRequest): Response<PollingResponse>

    /**
     * Send data to a specific instrument.
     */
    @POST("harvester/send")
    suspend fun sendData(@Body request: SendDataRequest): Response<SendDataResponse>

    /**
     * Example authentication endpoint (Placeholder).
     * In a real scenario, this might point to Keycloak.
     */
    @POST("auth/token")
    suspend fun login(@Body credentials: Map<String, String>): Response<AuthResponse>
}
