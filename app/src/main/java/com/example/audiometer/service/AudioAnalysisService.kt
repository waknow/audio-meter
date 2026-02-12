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

    // Analysis - 保存样本的完整 MFCC 序列（而非平均值）
    private var targetMFCCSequence: List<FloatArray>? = null
    private var sampleFrameCount: Int = 0
    private val FRAME_SIZE = 1024
    private val HOP_LENGTH = 256  // 与 Python 一致的帧移
    
    // 滑动窗口缓冲区 - 存储最近的 MFCC 帧
    private val mfccBuffer = mutableListOf<FloatArray>()
    private val maxBufferSize = 100  // 最多缓存100帧

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
     * 加载样本的完整 MFCC 序列（保留时序信息）
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
            
            // 提取完整的 MFCC 序列（不计算平均值）
            val mfccSequence = mutableListOf<FloatArray>()
            var pos = 0
            while (pos + FRAME_SIZE <= floats.size) {
                val chunk = floats.sliceArray(pos until pos + FRAME_SIZE)
                val mfcc = featureExtractor.calculateMFCC(chunk, SAMPLE_RATE.toFloat())
                // 删除 C0（与 Python 对齐）
                val mfccWithoutC0 = mfcc.sliceArray(1 until mfcc.size)
                mfccSequence.add(mfccWithoutC0)
                pos += FRAME_SIZE  // 无重叠提取
            }
            
            targetMFCCSequence = mfccSequence
            sampleFrameCount = mfccSequence.size
            
            val durationMs = (floats.size * 1000.0 / SAMPLE_RATE).toInt()
            AnalysisStateHolder.addLog(
                "Sample Loaded: ${file.name} (${sampleFrameCount} frames, ${durationMs}ms)"
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
                var audioBufferPos = 0

                while (isRunning) {
                    val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readResult >= FRAME_SIZE) {
                        // 按 HOP_LENGTH 步长处理多个帧
                        var offset = 0
                        while (offset + FRAME_SIZE <= readResult) {
                            val floatChunk = FloatArray(FRAME_SIZE)
                            for (i in 0 until FRAME_SIZE) {
                                floatChunk[i] = buffer[offset + i].toFloat()
                            }

                            // 计算 MFCC 并删除 C0
                            val mfcc = featureExtractor.calculateMFCC(floatChunk, SAMPLE_RATE.toFloat())
                            val mfccWithoutC0 = mfcc.sliceArray(1 until mfcc.size)
                            
                            // 添加到滑动窗口缓冲区
                            mfccBuffer.add(mfccWithoutC0)
                            if (mfccBuffer.size > maxBufferSize) {
                                mfccBuffer.removeAt(0)
                            }

                            // 当缓冲区积累足够帧时，进行序列匹配
                            val distance = if (targetMFCCSequence != null && mfccBuffer.size >= sampleFrameCount) {
                                calculateSequenceDistance(
                                    mfccBuffer.takeLast(sampleFrameCount),
                                    targetMFCCSequence!!
                                )
                            } else {
                                Float.MAX_VALUE
                            }

                            // 转换为相似度百分比显示（距离越小越好）
                            val similarity = if (distance < Float.MAX_VALUE) {
                                // 映射到 0-100，阈值 35 对应约 65 分
                                maxOf(0f, 100f - distance * 2)
                            } else {
                                0f
                            }
                            
                            AnalysisStateHolder.updateSimilarity(similarity)

                            // 直接使用欧氏距离阈值
                            if (distance < configRepo.similarityThreshold) {
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastAlertTime > configRepo.sampleIntervalMs) {
                                    lastAlertTime = currentTime
                                    handleAlert(similarity, buffer.sliceArray(0 until readResult))
                                }
                            }
                            
                            offset += HOP_LENGTH
                            audioBufferPos += HOP_LENGTH
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


    /**
     * 计算 MFCC 序列间的平均欧氏距离（与 Python 实现一致）
     */
    private fun calculateSequenceDistance(
        sequence1: List<FloatArray>,
        sequence2: List<FloatArray>
    ): Float {
        if (sequence1.size != sequence2.size) return Float.MAX_VALUE
        
        var totalDistance = 0f
        for (i in sequence1.indices) {
            totalDistance += MFCCMatcher.calculateEuclideanDistance(sequence1[i], sequence2[i])
        }
        
        return totalDistance / sequence1.size
    }

    private fun stopAnalysis() {
        isRunning = false
        AnalysisStateHolder.setRunning(false)
        mfccBuffer.clear()
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


