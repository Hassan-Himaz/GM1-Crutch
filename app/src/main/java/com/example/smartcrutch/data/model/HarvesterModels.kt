package com.example.smartcrutch.data.model

import com.google.gson.annotations.SerializedName

/**
 * Represents data received from an instrument based on Guide Section 14.
 */
data class InstrumentData(
    @SerializedName("deviceDataId") val deviceDataId: Long,
    @SerializedName("uniqueId") val uniqueId: String,
    @SerializedName("dataValue") val dataValue: String, // Base64 encoded
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("timeHandled") val timeHandled: String,
    @SerializedName("packetType") val packetType: Int,
    @SerializedName("instrumentType") val instrumentType: String
)

/**
 * Response from the polling endpoint based on Guide Section 14.
 */
data class PollingResponse(
    @SerializedName("instrumentIdentifier") val instrumentIdentifier: String,
    @SerializedName("hasMore") val hasMore: Boolean,
    @SerializedName("nextCursor") val nextCursor: Long,
    @SerializedName("data") val data: List<InstrumentData>
)

/**
 * Request body for sending data based on Guide Section 14.
 */
data class SendDataRequest(
    @SerializedName("data") val dataValue: String, // Base64 encoded
    @SerializedName("timestamp") val timestamp: String
)

/**
 * Response from the send data endpoint based on Guide Section 14.
 */
data class SendDataResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String? = null
)

/**
 * Keycloak Auth Response.
 */
data class AuthResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("refresh_token") val refreshToken: String? = null
)
