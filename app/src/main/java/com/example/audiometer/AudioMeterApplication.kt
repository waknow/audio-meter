package com.example.audiometer

import android.app.Application
import com.example.audiometer.data.AppDatabase
import com.example.audiometer.data.ConfigRepository

class AudioMeterApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val configRepository by lazy { ConfigRepository(this) }
}

