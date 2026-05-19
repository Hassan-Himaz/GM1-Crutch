package com.example.smartcrutch.data.model

import com.google.gson.annotations.SerializedName

/**
 * Represents data received from an instrument.
 */
data class InstrumentData(
    @SerializedName("id") val id: String,
    @SerializedName("instrument_id") val instrumentId: String,
    @SerializedName("payload") val payload: String,
    @SerializedName("timestamp") val timestamp: Long
)

/**
 * Request body for polling data.
 */
data class PollingRequest(
    @SerializedName("instrument_id") val instrumentId: String,
    @SerializedName("since") val since: Long? = null
)

/**
 * Response from the polling endpoint.
 */
data class PollingResponse(
    @SerializedName("data") val data: List<InstrumentData>
)

/**
 * Request body for sending data to an instrument.
 */
data class SendDataRequest(
    @SerializedName("instrument_id") val instrumentId: String,
    @SerializedName("payload") val payload: String
)

/**
 * Response from the send data endpoint.
 */
data class SendDataResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String? = null
)

/**
 * Placeholder for Auth Response if needed (e.g., from Keycloak).
 */
data class AuthResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("refresh_token") val refreshToken: String? = null
)
