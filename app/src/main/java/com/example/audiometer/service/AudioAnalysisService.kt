package com.example.audiometer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.audiometer.AudioMeterApplication
import com.example.audiometer.R
import com.example.audiometer.data.ConfigRepository
import com.example.audiometer.service.MicrophoneAudioSource
import com.example.audiometer.service.AlertHandler
import com.example.audiometer.service.AudioAnalysisEngine
import com.example.audiometer.service.SampleLoader
import com.example.audiometer.util.AnalysisStateHolder
import com.example.audiometer.util.AudioFeatureExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 前台服务：持有麦克风权限，通过 [AudioAnalysisEngine] + [MicrophoneAudioSource]
 * 在后台持续进行实时音频分析。
 *
 * 分析逻辑、告警副作用均委托给 domain 层，Service 本身只负责
 * Android 生命周期管理与通知。
 */
class AudioAnalysisService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val featureExtractor = AudioFeatureExtractor()
    private var analysisJob: Job? = null
    private lateinit var configRepo: ConfigRepository

    companion object {
        const val CHANNEL_ID = "AudioMeterChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        configRepo = (application as AudioMeterApplication).configRepository
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopAnalysis()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        if (!AnalysisStateHolder.isRunning.value) {
            startAnalysis()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopAnalysis()
        serviceScope.cancel()
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────────

    private fun startAnalysis() {
        AnalysisStateHolder.setRunning(true)
        AnalysisStateHolder.resetStats()

        val alertHandler = AlertHandler(this, serviceScope)
        val engine = AudioAnalysisEngine(
            featureExtractor = featureExtractor,
            onMatch = { similarity, chunk -> alertHandler.handleAlert(similarity, chunk) },
            getThreshold = { configRepo.similarityThreshold },
            getIntervalMs = { configRepo.sampleIntervalMs },
        )

        analysisJob = serviceScope.launch {
            try {
                val samplePath = configRepo.sampleAudioPath
                if (samplePath.isNullOrEmpty()) {
                    AnalysisStateHolder.addLog("No sample audio configured")
                    return@launch
                }
                val sampleFile = File(samplePath)
                val fingerprint = SampleLoader.load(sampleFile, featureExtractor)
                if (fingerprint == null) {
                    AnalysisStateHolder.addLog("Failed to load sample fingerprint")
                    return@launch
                }
                AnalysisStateHolder.addLog("Sample Loaded: ${sampleFile.name}")
                AnalysisStateHolder.addLog("Recording started")

                engine.run(MicrophoneAudioSource(), fingerprint, isSimulation = false)
            } catch (e: Exception) {
                AnalysisStateHolder.addLog("Error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun stopAnalysis() {
        AnalysisStateHolder.setRunning(false)
        analysisJob?.cancel()
        analysisJob = null
        AnalysisStateHolder.addLog("Recording stopped")
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Meter Running")
            .setContentText("Analyzing audio in background...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Meter Channel",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Channel for Audio Meter Service" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}

