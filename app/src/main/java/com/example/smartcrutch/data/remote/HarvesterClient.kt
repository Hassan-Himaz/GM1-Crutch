package com.example.smartcrutch.data.remote

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

object HarvesterClient {

    private const val BASE_URL = "https://api.bariumbogota.com/" // Placeholder URL
    private var authToken: String? = "PLACEHOLDER_JWT_TOKEN" // Placeholder token

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    /**
     * Interceptor to add Authorization header and handle 403 Forbidden.
     */
    private val authInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        
        val authenticatedRequest = if (authToken != null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $authToken")
                .build()
        } else {
            originalRequest
        }

        val response = chain.proceed(authenticatedRequest)

        if (response.code == 403) {
            // Handle Forbidden error (e.g., token expired or insufficient permissions)
            // In a real app, you might trigger a re-authentication flow here.
            println("ERROR: 403 Forbidden - Check your credentials and permissions.")
        }

        response
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor(authInterceptor)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .client(okHttpClient)
        .build()

    val apiService: HarvesterApiService = retrofit.create(HarvesterApiService::class.java)

    /**
     * Updates the auth token. 
     * Call this after a successful login.
     */
    fun updateToken(newToken: String) {
        authToken = newToken
    }
}
