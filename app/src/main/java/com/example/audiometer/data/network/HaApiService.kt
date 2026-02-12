package com.example.audiometer.data.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

interface HaApiService {
    @POST
    suspend fun sendAlert(@Url url: String, @Body data: HaAlertData): Response<Void>
}

