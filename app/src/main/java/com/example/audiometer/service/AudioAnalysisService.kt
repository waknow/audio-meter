package com.example.audiometer.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.audiometer.AudioMeterApplication
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
import java.util.Locale

class AudioAnalysisService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var audioRecord: AudioRecord? = null
    private val featureExtractor = AudioFeatureExtractor()

    private lateinit var configRepo: ConfigRepository

    // Analysis
    private var targetFingerprint: FloatArray? = null
    private val FRAME_SIZE = 1024

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
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Meter Running")
            .setContentText("Analyzing audio in background...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
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
            targetFingerprint = featureExtractor.computeAverageMFCC(floats, FRAME_SIZE, SAMPLE_RATE.toFloat())
            AnalysisStateHolder.addLog("Sample Loaded: ${file.name}")
        } catch (e: Exception) {
            AnalysisStateHolder.addLog("Failed to load sample: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
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
                    ), FRAME_SIZE * 2
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
                    if (readResult >= FRAME_SIZE) {
                        // Extract latest window for MFCC from end
                        val floatChunk = FloatArray(FRAME_SIZE)
                        val offset = readResult - FRAME_SIZE
                        for (i in 0 until FRAME_SIZE) {
                            floatChunk[i] = buffer[offset + i].toFloat()
                        }

                        val currentMFCC = featureExtractor.calculateMFCC(floatChunk, SAMPLE_RATE.toFloat())

                        val similarity = if (targetFingerprint != null) {
                            featureExtractor.calculateSimilarity(currentMFCC, targetFingerprint!!)
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
            AnalysisStateHolder.addLog("Alert! Match: ${String.format(Locale.US, "%.1f", similarity)}%")

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
        val name = "Audio Meter Channel"
        val descriptionText = "Channel for Audio Meter Service"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}


