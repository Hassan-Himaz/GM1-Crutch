package com.example.smartcrutch.data.remote

import com.example.smartcrutch.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface HarvesterApiService {

    /**
     * Poll for data from a specific instrument (Guide Section 14).
     * @param instrumentId The identifier of the instrument.
     * @param cursor The DeviceDataId to start polling from.
     * @param limit Max records to return.
     */
    @GET("api/v1/harvester/instruments/{id}/data")
    suspend fun pollData(
        @Path("id") instrumentId: String,
        @Query("since") cursor: Long? = null,
        @Query("limit") limit: Int = 100
    ): Response<PollingResponse>

    /**
     * Send data to a specific instrument (Guide Section 14).
     */
    @POST("api/v1/harvester/instruments/{id}/data")
    suspend fun sendData(
        @Path("id") instrumentId: String,
        @Body request: SendDataRequest
    ): Response<SendDataResponse>

    /**
     * Keycloak token endpoint for OAuth2 password grant.
     */
    @FormUrlEncoded
    @POST
    suspend fun login(
        @Url url: String,
        @Field("grant_type") grantType: String,
        @Field("client_id") clientId: String,
        @Field("username") username: String,
        @Field("password") password: String
    ): Response<AuthResponse>
}
