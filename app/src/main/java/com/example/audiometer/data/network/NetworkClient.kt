package com.example.audiometer.data.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkClient {
    private var retrofit: Retrofit? = null

    // We use a base URL placeholder because the actual URL is dynamic per request in this app's logic
    private const val BASE_URL = "http://localhost/"

    val apiService: HaApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HaApiService::class.java)
    }
}

