package com.example.audiometer.service

import android.content.Context
import com.example.audiometer.AudioMeterApplication
import com.example.audiometer.data.ValidationRecord
import com.example.audiometer.data.network.HaAlertData
import com.example.audiometer.data.network.NetworkClient
import com.example.audiometer.util.AnalysisStateHolder
import com.example.audiometer.util.MFCCMatcher
import com.example.audiometer.util.WavUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * 告警处理器：当分析引擎检测到匹配事件时，负责
 * 1. 保存触发帧的 WAV 片段到外部存储；
 * 2. 将记录插入 Room 数据库；
 * 3. 上传通知到 Home Assistant（如已配置）。
 *
 * 从 [AudioAnalysisService][com.example.audiometer.service.AudioAnalysisService] 中抽离，
 * 使告警副作用与分析引擎解耦，便于独立测试。
 */
class AlertHandler(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        const val MAX_FILES = 20
    }

    /**
     * 异步处理一次告警事件（在 [scope] 内启动协程，不阻塞调用方）。
     *
     * @param similarity 当前帧的相似度（0–100）。
     * @param audioChunk 触发告警的 PCM 帧（Float 形式 16 位 PCM，与 [MicrophoneAudioSource][com.example.audiometer.service.MicrophoneAudioSource] 产出格式一致）。
     */
    fun handleAlert(similarity: Float, audioChunk: FloatArray) {
        scope.launch {
            AnalysisStateHolder.addLog("Alert! Match: ${String.format(Locale.US, "%.1f", similarity)}%")
            cleanupOldFiles()

            var savedPath: String? = null
            try {
                val timestamp = System.currentTimeMillis()
                val fileName = "alert_$timestamp.wav"
                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
                if (dir != null) {
                    val file = File(dir, fileName)
                    val shorts = ShortArray(audioChunk.size) { audioChunk[it].toInt().toShort() }
                    WavUtil.saveWav(file, shorts, MFCCMatcher.SAMPLE_RATE)
                    savedPath = file.absolutePath
                    AnalysisStateHolder.addLog("Saved to $fileName")
                }
            } catch (e: Exception) {
                AnalysisStateHolder.addLog("Failed to save audio: ${e.message}")
            }

            val app = context.applicationContext as AudioMeterApplication
            val record = ValidationRecord(
                timestamp = System.currentTimeMillis(),
                similarity = similarity,
                threshold = app.configRepository.similarityThreshold,
                audioPath = savedPath
            )
            app.database.validationRecordDao().insert(record)

            val url = app.configRepository.haUrl
            if (url.isNotEmpty()) {
                try {
                    val data = HaAlertData(
                        timestamp = record.timestamp,
                        similarity = record.similarity,
                        message = "Audio Match Detected"
                    )
                    val response = NetworkClient.apiService.sendAlert(url, data)
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
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: return
            val files = dir.listFiles { _, name -> name.startsWith("alert_") && name.endsWith(".wav") }
            if (files != null && files.size >= MAX_FILES) {
                files.sortBy { it.lastModified() }
                val toDelete = files.size - MAX_FILES + 1
                for (i in 0 until toDelete) {
                    files[i].delete()
                }
                AnalysisStateHolder.addLog("Cleaned up $toDelete old files")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
