package com.example.audiometer.data

import android.content.Context
import android.content.SharedPreferences

class ConfigRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("audiometer_config", Context.MODE_PRIVATE)

    var similarityThreshold: Float
        get() = prefs.getFloat("similarity_threshold", 80.0f)
        set(value) = prefs.edit().putFloat("similarity_threshold", value).apply()

    var sampleIntervalMs: Long
        get() = prefs.getLong("sample_interval_ms", 1000L)
        set(value) = prefs.edit().putLong("sample_interval_ms", value).apply()

    var haUrl: String
        get() = prefs.getString("ha_url", "") ?: ""
        set(value) = prefs.edit().putString("ha_url", value).apply()

    var sampleAudioPath: String?
        get() = prefs.getString("sample_audio_path", null)
        set(value) = prefs.edit().putString("sample_audio_path", value).apply()
}

