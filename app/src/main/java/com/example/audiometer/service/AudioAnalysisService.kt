package com.example.audiometer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.audiometer.AudioMeterApplication
import com.example.audiometer.MainActivity
import com.example.audiometer.R
import com.example.audiometer.data.ConfigRepository
import com.example.audiometer.data.ValidationRecord
import com.example.audiometer.utils.AnalysisStateHolder
import com.example.audiometer.utils.AudioFeatureExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log

class AudioAnalysisService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var audioRecord: AudioRecord? = null
    private val featureExtractor = AudioFeatureExtractor()

    private lateinit var configRepo: ConfigRepository

    // Analysis
    private var targetFingerprint: FloatArray? = null
    private val FFT_SIZE = 512

    companion object {
        const val CHANNEL_ID = "AudioMeterChannel"
        const val NOTIFICATION_ID = 1
        const val SAMPLE_RATE = 44100
        const val MAX_FILES = 20
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

        startForegroundService()
        if (!isRunning) {
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

    private fun startForegroundService() {
        val stopIntent = Intent(this, AudioAnalysisService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Meter Running")
            .setContentText("Analyzing audio in background...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(R.drawable.ic_launcher_foreground, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun loadSampleFingerprint() {
        val path = configRepo.sampleAudioPath
        if (path.isNullOrEmpty()) {
            AnalysisStateHolder.addLog("No sample audio configured")
            return
        }
        val file = File(path)
        if (!file.exists()) {
            AnalysisStateHolder.addLog("Sample file not found")
            return
        }
        try {
            val samples = com.example.audiometer.utils.WavUtil.loadWav(file)
            if (samples.isEmpty()) return

            val floats = FloatArray(samples.size) { samples[it].toFloat() }
            targetFingerprint = featureExtractor.computeAverageSpectrum(floats, FFT_SIZE)
            AnalysisStateHolder.addLog("Sample Loaded: ${file.name}")
        } catch (e: Exception) {
            AnalysisStateHolder.addLog("Failed to load sample: ${e.message}")
        }
    }

    private fun startAnalysis() {
        isRunning = true
        AnalysisStateHolder.setRunning(true)

        serviceScope.launch {
            try {
                // Load Sample
                loadSampleFingerprint()

                val bufferSize = maxOf(
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ), FFT_SIZE * 2
                )

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                audioRecord?.startRecording()
                AnalysisStateHolder.addLog("Recording started")

                val buffer = ShortArray(bufferSize)

                var lastAlertTime = 0L

                while (isRunning) {
                    val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readResult >= FFT_SIZE) {
                        // Extract latest window for FFT from end
                        val floatChunk = FloatArray(FFT_SIZE)
                        val offset = readResult - FFT_SIZE
                        for (i in 0 until FFT_SIZE) {
                            floatChunk[i] = buffer[offset + i].toFloat()
                        }

                        val currentSpec = featureExtractor.calculateFFT(floatChunk)

                        val similarity = if (targetFingerprint != null) {
                            featureExtractor.calculateSimilarity(currentSpec, targetFingerprint!!)
                        } else {
                            0f
                        }

                        AnalysisStateHolder.updateSimilarity(similarity)

                        if (similarity >= configRepo.similarityThreshold) {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastAlertTime > configRepo.sampleIntervalMs) {
                                lastAlertTime = currentTime
                                handleAlert(similarity, buffer.sliceArray(0 until readResult))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AnalysisStateHolder.addLog("Error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun handleAlert(similarity: Float, audioData: ShortArray) {
        serviceScope.launch {
            AnalysisStateHolder.addLog("Alert! Match: ${String.format("%.1f", similarity)}%")

            cleanupOldFiles()

            // Save to File
            var savedPath: String? = null
            try {
                val timestamp = System.currentTimeMillis()
                val fileName = "alert_$timestamp.wav"
                // Save to app-specific external storage to avoid permission mess on A13+ for generic external
                val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
                if (dir != null) {
                    val file = File(dir, fileName)
                    com.example.audiometer.utils.WavUtil.saveWav(file, audioData)
                    savedPath = file.absolutePath
                    AnalysisStateHolder.addLog("Saved to $fileName")
                }
            } catch (e: Exception) {
                AnalysisStateHolder.addLog("Failed to save audio: ${e.message}")
            }

            // Save to DB
            val record = ValidationRecord(
                timestamp = System.currentTimeMillis(),
                similarity = similarity,
                threshold = configRepo.similarityThreshold,
                audioPath = savedPath
            )
            val db = (application as AudioMeterApplication).database
            db.validationRecordDao().insert(record)

            // Upload to HA
            val url = configRepo.haUrl
            if (url.isNotEmpty()) {
                try {
                    val data = com.example.audiometer.data.network.HaAlertData(
                        timestamp = record.timestamp,
                        similarity = record.similarity,
                        message = "Audio Match Detected"
                    )
                    val response = com.example.audiometer.data.network.NetworkClient.apiService.sendAlert(url, data)
                    if (response.isSuccessful) {
                        AnalysisStateHolder.addLog("HA Upload Success")
                    } else {
                        AnalysisStateHolder.addLog("HA Upload Failed: ${response.code()}")
                    }
                } catch (e: Exception) {
                    AnalysisStateHolder.addLog("HA Error: ${e.message}")
                }
            }
        }
    }

    private fun cleanupOldFiles() {
        try {
            val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: return
            val files = dir.listFiles { _, name -> name.startsWith("alert_") && name.endsWith(".wav") }
            if (files != null && files.size >= MAX_FILES) {
                files.sortBy { it.lastModified() }
                val toDelete = files.size - MAX_FILES + 1
                for (i in 0 until toDelete) {
                    files[i].delete()
                }
                AnalysisStateHolder.addLog("Cleaned up ${toDelete} old files")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun stopAnalysis() {
        isRunning = false
        AnalysisStateHolder.setRunning(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
        AnalysisStateHolder.addLog("Recording stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Audio Meter Channel"
            val descriptionText = "Channel for Audio Meter Service"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}



