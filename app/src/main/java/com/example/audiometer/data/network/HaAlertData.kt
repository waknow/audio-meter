package com.example.audiometer.data.network

import com.google.gson.annotations.SerializedName

data class HaAlertData(
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("similarity") val similarity: Float,
    @SerializedName("message") val message: String
)

