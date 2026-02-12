package com.example.audiometer.ui

import android.app.Application
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audiometer.AudioMeterApplication
import com.example.audiometer.data.ValidationRecord
import com.example.audiometer.service.AudioAnalysisService
import com.example.audiometer.utils.AnalysisStateHolder
import com.example.audiometer.utils.MFCCMatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OfflineAnalysisResult(
    val timestamp: Long,
    val similarity: Float,
    val threshold: Float
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as AudioMeterApplication).database
    private val configRepo = (application as AudioMeterApplication).configRepository

    val isRunning = AnalysisStateHolder.isRunning
    val currentSimilarity = AnalysisStateHolder.currentSimilarity
    val logs = AnalysisStateHolder.logs
    val totalChecks = AnalysisStateHolder.totalChecks

    // Player state
    private var mediaPlayer: MediaPlayer? = null
    private var samplePlayer: MediaPlayer? = null

    var isSamplePlaying by mutableStateOf(false)
        internal set

    // Offline Results persistence
    var offlineResults by mutableStateOf<List<OfflineAnalysisResult>>(emptyList())
        internal set
    var offlineWavInfo by mutableStateOf<com.example.audiometer.utils.WavInfo?>(null)
        internal set
    var offlineResultMessage by mutableStateOf("Select a file to analyze")
        internal set
    var currentAnalyzedFile by mutableStateOf<java.io.File?>(null)
        internal set

    val history: StateFlow<List<ValidationRecord>> = db.validationRecordDao().getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Config Stata
    var threshold: Float
        get() = configRepo.similarityThreshold
        set(value) { configRepo.similarityThreshold = value }

    var interval: Long
        get() = configRepo.sampleIntervalMs
        set(value) { configRepo.sampleIntervalMs = value }

    var haUrl: String
        get() = configRepo.haUrl
        set(value) { configRepo.haUrl = value }

    fun updateSampleAudioPath(path: String) {
        configRepo.sampleAudioPath = path
    }

    val sampleAudioPath: String?
        get() = configRepo.sampleAudioPath

    fun playSampleAudio() {
        if (samplePlayer?.isPlaying == true) {
            stopSampleAudio()
            return
        }

        val path = sampleAudioPath ?: return
        try {
            isSamplePlaying = true
            samplePlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
                setOnCompletionListener {
                    it.release()
                    samplePlayer = null
                    isSamplePlaying = false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isSamplePlaying = false
        }
    }

    fun stopSampleAudio() {
        samplePlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        samplePlayer = null
        isSamplePlaying = false
    }

    fun playClip(file: java.io.File, timestamp: Long) {
        // Stop any existing playback
        stopPlayback()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()

                val startMs = (timestamp - 1000).coerceAtLeast(0)
                seekTo(startMs.toInt())
                start() // Start immediately

                // Stop after 2 seconds (or less if at start)
                // We use a handler or coroutine to stop it?
                // For simplicity, let's just use a coroutine in viewModelScope
            }

            viewModelScope.launch {
                // Wait for 2 seconds then stop
                kotlinx.coroutines.delay(2000)
                if (mediaPlayer?.isPlaying == true) {
                    try {
                        mediaPlayer?.pause()
                    } catch (_: Exception) { /* ignore */ }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopPlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
    }

    fun clearOfflineResults() {
        offlineResults = emptyList()
        offlineWavInfo = null
        offlineResultMessage = "Select a file to analyze"
        currentAnalyzedFile = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        stopSampleAudio()
    }

    fun toggleService() {
        val context = getApplication<AudioMeterApplication>()
        if (isRunning.value) {
            val intent = Intent(context, AudioAnalysisService::class.java).apply {
                action = "STOP"
            }
            context.startService(intent)
        } else {
            val intent = Intent(context, AudioAnalysisService::class.java)
            context.startForegroundService(intent)
        }
    }

    suspend fun analyzeOfflineFile(
        file: java.io.File,
        onProgress: (Float) -> Unit
    ): Pair<com.example.audiometer.utils.WavInfo?, List<OfflineAnalysisResult>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        currentAnalyzedFile = file
        val matches = mutableListOf<OfflineAnalysisResult>()
        val extractor = com.example.audiometer.utils.AudioFeatureExtractor()
        val frameSize = 1024
        val hopLength = 256  // 与 Python 一致的帧移
        val SAMPLE_RATE = 16000  // 使用 16kHz

        // 1. Load Target as MFCC Sequence
        val targetPath = configRepo.sampleAudioPath
        if (targetPath.isNullOrEmpty()) {
            offlineResultMessage = "No sample audio set"
            return@withContext Pair(null, emptyList())
        }
        val targetFile = java.io.File(targetPath)
        if (!targetFile.exists()) {
            offlineResultMessage = "Sample file not found"
            return@withContext Pair(null, emptyList())
        }

        // Get Input File Info
        val wavInfo = com.example.audiometer.utils.WavUtil.getWavInfo(file)
        offlineWavInfo = wavInfo

        // Load target and extract MFCC sequence
        val targetSamples = com.example.audiometer.utils.WavUtil.loadWav(targetFile)
        if (targetSamples.isEmpty()) {
            offlineResultMessage = "Could not load sample"
            return@withContext Pair(wavInfo, emptyList())
        }
        
        val targetFloats = FloatArray(targetSamples.size) { targetSamples[it].toFloat() }
        
        // 提取样本的完整 MFCC 序列
        val targetMFCCSequence = mutableListOf<FloatArray>()
        var pos = 0
        while (pos + frameSize <= targetFloats.size) {
            val chunk = targetFloats.sliceArray(pos until pos + frameSize)
            val mfcc = extractor.calculateMFCC(chunk, SAMPLE_RATE.toFloat())
            // 删除 C0 与 Python 对齐
            val mfccWithoutC0 = mfcc.sliceArray(1 until mfcc.size)
            targetMFCCSequence.add(mfccWithoutC0)
            pos += frameSize
        }
        
        val sampleFrameCount = targetMFCCSequence.size
        if (sampleFrameCount == 0) {
            offlineResultMessage = "Sample too short"
            return@withContext Pair(wavInfo, emptyList())
        }

        // 2. Load Input File
        val inputSamples = com.example.audiometer.utils.WavUtil.loadWav(file)
        if (inputSamples.isEmpty()) {
            offlineResultMessage = "Could not load analysis file"
            return@withContext Pair(wavInfo, emptyList())
        }
        
        val inputFloats = FloatArray(inputSamples.size) { inputSamples[it].toFloat() }

        // 3. 滑动窗口分析（与 Python 一致）
        val threshold = 100f - configRepo.similarityThreshold  // 转换为欧氏距离阈值
        val totalSamples = inputFloats.size
        var lastProgress = 0f
        
        // 提取输入文件的 MFCC 缓冲区
        val mfccBuffer = mutableListOf<FloatArray>()
        pos = 0
        var frameIdx = 0
        
        while (pos + frameSize <= totalSamples) {
            // Update progress
            val currentProgress = pos.toFloat() / totalSamples
            if (currentProgress - lastProgress >= 0.01) {
                onProgress(currentProgress)
                lastProgress = currentProgress
            }

            // 提取 MFCC
            val chunk = inputFloats.sliceArray(pos until pos + frameSize)
            val mfcc = extractor.calculateMFCC(chunk, SAMPLE_RATE.toFloat())
            val mfccWithoutC0 = mfcc.sliceArray(1 until mfcc.size)
            
            mfccBuffer.add(mfccWithoutC0)
            
            // 当缓冲区积累足够帧时，进行序列匹配
            if (mfccBuffer.size >= sampleFrameCount) {
                val testSequence = mfccBuffer.takeLast(sampleFrameCount)
                
                // 计算平均欧氏距离
                var totalDistance = 0f
                for (i in 0 until sampleFrameCount) {
                    totalDistance += MFCCMatcher.calculateEuclideanDistance(
                        testSequence[i],
                        targetMFCCSequence[i]
                    )
                }
                val avgDistance = totalDistance / sampleFrameCount
                
                // 转换为相似度显示
                val similarity = maxOf(0f, 100f - avgDistance * 2)
                
                if (avgDistance < threshold) {
                    val timeMs = (pos.toLong() * 1000) / SAMPLE_RATE
                    matches.add(OfflineAnalysisResult(timeMs, similarity, configRepo.similarityThreshold))
                    
                    // 跳过一段避免重复匹配
                    pos += sampleFrameCount * frameSize
                    mfccBuffer.clear()
                } else {
                    pos += hopLength
                    // 保持缓冲区大小
                    if (mfccBuffer.size > sampleFrameCount) {
                        mfccBuffer.removeAt(0)
                    }
                }
            } else {
                pos += hopLength
            }
            
            frameIdx++
        }

        onProgress(1.0f)

        offlineResults = matches
        offlineResultMessage = "Found ${matches.size} occurrences"

        Pair(wavInfo, matches)
    }
}
