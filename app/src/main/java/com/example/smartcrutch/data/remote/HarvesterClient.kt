package com.example.smartcrutch.data.remote

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object HarvesterClient {

    private const val AUTH_URL = "https://keycloaksoftdev.l2s2.com/realms/SoftSilicon/protocol/openid-connect/token"
    private const val API_BASE_URL = "https://apisoftdev.l2s2.com/"
    
    // Default instrument identifier provided by user
    const val DEFAULT_INSTRUMENT_ID = "4779fbb9b035ce55"
    
    private var authToken: String? = null

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    /**
     * Interceptor to add Authorization header and handle 401/403 errors.
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

        if (response.code == 403 || response.code == 401) {
            println("ERROR: Auth Failed (${response.code}) - Check your credentials.")
        }

        response
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor(authInterceptor)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(API_BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .client(okHttpClient)
        .build()

    val apiService: HarvesterApiService = retrofit.create(HarvesterApiService::class.java)

    /**
     * Performs login using the provided credentials.
     */
    suspend fun login(): Boolean {
        return try {
            val response = apiService.login(
                url = AUTH_URL,
                grantType = "password",
                clientId = "cg-harvester-public-api",
                username = "foundation001",
                password = "Foundation123!"
            )
            
            if (response.isSuccessful) {
                authToken = response.body()?.accessToken
                true
            } else {
                false
            }
        } catch (e: Exception) {
            // Log the error but don't crash the app
            println("Login Network Error: ${e.message}")
            false
        }
    }
}
