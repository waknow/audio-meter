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
import com.example.audiometer.utils.MFCCMatcher
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

    // Analysis - 保存样本的代表性特征
    private var bestSampleMFCC: FloatArray? = null
    private val FRAME_SIZE = 1024   // 与离线分析（MFCCMatcher.detectMatches）一致
    private val HOP_LENGTH = 256    // 与离线分析（MFCCMatcher.detectMatches）一致
    
    companion object {
        const val CHANNEL_ID = "AudioMeterChannel"
        const val NOTIFICATION_ID = 1
        const val SAMPLE_RATE = 16000  // 改为 16000Hz 与 Python 对齐
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

    /**
     * 加载样本的代表性特征
     */
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
            
            // 如果样本文件采样率与录音采样率不同，重采样到 SAMPLE_RATE（与离线分析一致）
            val wavInfo = com.example.audiometer.utils.WavUtil.getWavInfo(file)
            val fileSampleRate = wavInfo?.sampleRate?.toFloat() ?: SAMPLE_RATE.toFloat()
            val processedFloats = if (fileSampleRate != SAMPLE_RATE.toFloat()) {
                MFCCMatcher.resample(floats, fileSampleRate, SAMPLE_RATE.toFloat())
            } else {
                floats
            }
            
            // 使用 MFCCMatcher 内部逻辑寻找最强帧特征
            bestSampleMFCC = MFCCMatcher.findBestRepresentativeMFCC(
                audio = processedFloats,
                frameSize = FRAME_SIZE,
                sampleRate = SAMPLE_RATE.toFloat(),
                extractor = featureExtractor
            )
            
            val durationMs = (processedFloats.size * 1000.0 / SAMPLE_RATE).toInt()
            AnalysisStateHolder.addLog(
                "Sample Loaded: ${file.name} (Length: ${durationMs}ms, BestFrame found: ${bestSampleMFCC != null})"
            )
        } catch (e: Exception) {
            AnalysisStateHolder.addLog("Failed to load sample: ${e.message}")
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAnalysis() {
        isRunning = true
        AnalysisStateHolder.setRunning(true)
        AnalysisStateHolder.resetStats()

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

                val readBuffer = ShortArray(FRAME_SIZE) // 每次读取一帧大小的数据
                val slidingBuffer = ShortArray(FRAME_SIZE * 2) // 较大的滑动窗口缓冲区
                var slidingBufferCount = 0
                
                var lastAlertTime = 0L

                while (isRunning) {
                    val readResult = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: 0
                    if (readResult > 0) {
                        // 将新读取的数据放入滑动缓冲区
                        for (i in 0 until readResult) {
                            if (slidingBufferCount < slidingBuffer.size) {
                                slidingBuffer[slidingBufferCount++] = readBuffer[i]
                            }
                        }

                        // 处理缓存中的完整帧
                        var frameCount = 0
                        while (slidingBufferCount >= FRAME_SIZE) {
                            val floatChunk = FloatArray(FRAME_SIZE)
                            for (i in 0 until FRAME_SIZE) {
                                floatChunk[i] = slidingBuffer[i].toFloat()
                            }

                            // 计算音频能量（用于调试）
                            val audioLevel = featureExtractor.calculateEnergy(floatChunk)
                            
                            // 计算 MFCC (1..12)
                            val mfcc = featureExtractor.calculateMFCC(floatChunk, SAMPLE_RATE.toFloat())
                            val mfccWithoutC0 = mfcc.sliceArray(1 until mfcc.size)
                            
                            // 匹配对比
                            val distance = if (bestSampleMFCC != null) {
                                featureExtractor.calculateEuclideanDistance(mfccWithoutC0, bestSampleMFCC!!)
                            } else {
                                Float.MAX_VALUE
                            }

                            val similarity = if (distance < Float.MAX_VALUE) {
                                maxOf(0f, 100f - (distance / 70f * 100f))
                            } else {
                                0f
                            }
                            
                            AnalysisStateHolder.updateSimilarity(similarity, distance, audioLevel)
                            
                            // 每50帧记录一次详细日志
                            if (frameCount % 50 == 0) {
                                AnalysisStateHolder.addLog("Frame #$frameCount: Dist=${String.format(Locale.US, "%.2f", distance)}, Level=${String.format(Locale.US, "%.0f", audioLevel)}")
                            }
                            frameCount++

                            if (distance < configRepo.similarityThreshold) {
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastAlertTime > configRepo.sampleIntervalMs) {
                                    lastAlertTime = currentTime
                                    AnalysisStateHolder.incrementMatchCount()
                                    handleAlert(similarity, slidingBuffer.sliceArray(0 until FRAME_SIZE))
                                }
                            }
                            
                            // 每次移动 HOP_LENGTH
                            val remaining = slidingBufferCount - HOP_LENGTH
                            for (i in 0 until remaining) {
                                slidingBuffer[i] = slidingBuffer[i + HOP_LENGTH]
                            }
                            slidingBufferCount = remaining
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
                    com.example.audiometer.utils.WavUtil.saveWav(file, audioData, SAMPLE_RATE)
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


