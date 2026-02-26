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
import java.io.File

data class OfflineAnalysisResult(
    val timestamp: Long,
    val similarity: Float,
    val threshold: Float
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as AudioMeterApplication).database
    private val configRepo = (application as AudioMeterApplication).configRepository
    private val simulator = com.example.audiometer.RealTimeLogicSimulator(application)

    val isRunning = AnalysisStateHolder.isRunning
    val currentSimilarity = AnalysisStateHolder.currentSimilarity
    val logs = AnalysisStateHolder.logs
    val totalChecks = AnalysisStateHolder.totalChecks
    val matchCount = AnalysisStateHolder.matchCount
    val currentDistance = AnalysisStateHolder.currentDistance
    val audioLevel = AnalysisStateHolder.audioLevel
    val lastProcessedTime = AnalysisStateHolder.lastProcessedTime

    // Player state
    private var mediaPlayer: MediaPlayer? = null
    private var samplePlayer: MediaPlayer? = null

    var isSamplePlaying by mutableStateOf(false)
        internal set
    
    var isOfflineFilePlaying by mutableStateOf(false)
        internal set

    // Offline Results persistence
    var offlineResults by mutableStateOf<List<OfflineAnalysisResult>>(emptyList())
        internal set
    var offlineWavInfo by mutableStateOf<com.example.audiometer.utils.WavInfo?>(null)
        internal set
    var sampleWavInfo by mutableStateOf<com.example.audiometer.utils.WavInfo?>(null)
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

    fun updateOfflineAudioPath(path: String) {
        configRepo.offlineAudioPath = path
    }

    val offlineAudioPath: String?
        get() = configRepo.offlineAudioPath

    fun playSampleAudio() {
        if (samplePlayer?.isPlaying == true) {
            stopSampleAudio()
            return
        }

        val path = sampleAudioPath
        if (path.isNullOrEmpty()) {
            android.util.Log.e("MainViewModel", "Sample audio path is null or empty")
            return
        }
        
        val file = java.io.File(path)
        if (!file.exists()) {
            android.util.Log.e("MainViewModel", "Sample file not found: $path")
            return
        }
        
        try {
            android.util.Log.d("MainViewModel", "Playing sample file: $path")
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
            android.util.Log.e("MainViewModel", "Error playing sample audio", e)
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

        if (!file.exists()) {
            android.util.Log.e("MainViewModel", "File not found for playback: ${file.absolutePath}")
            return
        }

        try {
            val startMs = (timestamp - 1000).coerceAtLeast(0)
            android.util.Log.d("MainViewModel", "Playing clip: ${file.name} from ${startMs}ms (Match at ${timestamp}ms)")
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
                setOnErrorListener { _, what, extra ->
                    android.util.Log.e("MainViewModel", "MediaPlayer error: what=$what, extra=$extra")
                    true
                }
                prepare()
                seekTo(startMs.toInt())
                start()
            }

            viewModelScope.launch {
                // Play for 3 seconds instead of 2 to give more context
                kotlinx.coroutines.delay(3000)
                if (mediaPlayer?.isPlaying == true) {
                    try {
                        android.util.Log.d("MainViewModel", "Clip playback finished (3s limit)")
                        mediaPlayer?.pause()
                    } catch (_: Exception) { /* ignore */ }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Error playing clip", e)
            e.printStackTrace()
        }
    }
    
    fun playOfflineFile() {
        if (mediaPlayer?.isPlaying == true) {
            stopPlayback()
            return
        }
        
        val file = currentAnalyzedFile
        if (file == null) {
            android.util.Log.e("MainViewModel", "No offline file to play")
            return
        }
        
        if (!file.exists()) {
            android.util.Log.e("MainViewModel", "Offline file not found: ${file.absolutePath}")
            return
        }
        
        try {
            android.util.Log.d("MainViewModel", "Playing offline file: ${file.absolutePath}")
            isOfflineFilePlaying = true
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    isOfflineFilePlaying = false
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Error playing offline file", e)
            e.printStackTrace()
            isOfflineFilePlaying = false
        }
    }

    fun stopPlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        isOfflineFilePlaying = false
    }

    fun clearOfflineResults() {
        offlineResults = emptyList()
        offlineWavInfo = null
        sampleWavInfo = null
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
            simulator.stop()
            val intent = Intent(context, AudioAnalysisService::class.java).apply {
                action = "STOP"
            }
            context.startService(intent)
        } else {
            val intent = Intent(context, AudioAnalysisService::class.java)
            context.startForegroundService(intent)
        }
    }

    /**
     * 使用本地文件模拟实时输入逻辑 (Debug 用)
     */
    fun startRealTimeSimulation(inputFile: File) {
        val samplePath = configRepo.sampleAudioPath
        if (samplePath != null) {
            val sampleFile = File(samplePath)
            if (sampleFile.exists()) {
                AnalysisStateHolder.setRunning(true)
                simulator.simulate(sampleFile, inputFile)
            } else {
                AnalysisStateHolder.addLog("Simulation Failed: Sample file not found at $samplePath")
            }
        } else {
            AnalysisStateHolder.addLog("Simulation Failed: No sample audio configured")
        }
    }

    suspend fun analyzeOfflineFile(
        file: java.io.File,
        onProgress: (Float) -> Unit
    ): Pair<com.example.audiometer.utils.WavInfo?, List<OfflineAnalysisResult>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        currentAnalyzedFile = file
        android.util.Log.d("MainViewModel", "Starting offline analysis for: ${file.absolutePath}")
        
        val matches = mutableListOf<OfflineAnalysisResult>()

        // 1. Get Input File Info FIRST (需要获取真实采样率)
        val wavInfo = com.example.audiometer.utils.WavUtil.getWavInfo(file)
        if (wavInfo == null) {
            android.util.Log.e("MainViewModel", "Failed to read WAV info from file")
            offlineResultMessage = "Invalid WAV file"
            return@withContext Pair(null, emptyList())
        }
        offlineWavInfo = wavInfo
        
        // 使用文件的实际采样率，而非硬编码 16000
        val actualSampleRate = wavInfo.sampleRate.toFloat()
        android.util.Log.d("MainViewModel", "WAV Info: sampleRate=${wavInfo.sampleRate}, duration=${wavInfo.durationMs}ms, channels=${wavInfo.channels}")

        // 2. Load Sample Audio
        val targetPath = configRepo.sampleAudioPath
        if (targetPath.isNullOrEmpty()) {
            android.util.Log.e("MainViewModel", "No sample audio path set")
            offlineResultMessage = "No sample audio set"
            return@withContext Pair(wavInfo, emptyList())
        }
        val targetFile = java.io.File(targetPath)
        if (!targetFile.exists()) {
            android.util.Log.e("MainViewModel", "Sample file not found: $targetPath")
            offlineResultMessage = "Sample file not found"
            return@withContext Pair(wavInfo, emptyList())
        }
        
        android.util.Log.d("MainViewModel", "Sample file: $targetPath (${targetFile.length()} bytes)")
        android.util.Log.d("MainViewModel", "Offline file: ${file.absolutePath} (${file.length()} bytes)")

        // 获取样本文件的采样率信息
        val targetWavInfo = com.example.audiometer.utils.WavUtil.getWavInfo(targetFile)
        
        // 如果采样率读取失败或为 0，尝试根据样本数估算
        var estimatedSampleRate: Int? = null
        if (targetWavInfo == null || targetWavInfo.sampleRate <= 0) {
            android.util.Log.w("MainViewModel", "Sample WAV info invalid or sampleRate=0, attempting estimation...")
            
            val fileSize = targetFile.length() - 44  // 减去 WAV 头
            val bytesPerSample = 2  // 假设 16-bit
            val estimatedSamples = fileSize / bytesPerSample
            
            // 优先：尝试基于实际加载的样本数和假设时长估算
            // 样本音频通常是 0.3-1 秒
            // 更安全的方法：假设与离线文件采样率相同
            estimatedSampleRate = wavInfo.sampleRate  // 使用离线文件的采样率
            
            android.util.Log.w("MainViewModel", "Using offline file's sample rate ($estimatedSampleRate Hz) for sample file")
            android.util.Log.w("MainViewModel", "Sample file: $estimatedSamples samples (estimated)")
            
            // 创建估算的 WavInfo
            val estimatedDuration = (estimatedSamples * 1000.0 / estimatedSampleRate).toLong()
            sampleWavInfo = com.example.audiometer.utils.WavInfo(
                sampleRate = estimatedSampleRate,
                channels = 1,
                bitDepth = 16,
                durationMs = estimatedDuration
            )
        } else {
            sampleWavInfo = targetWavInfo
            android.util.Log.d("MainViewModel", "Sample WAV Info: sampleRate=${targetWavInfo.sampleRate}, duration=${targetWavInfo.durationMs}ms")
            
            // 警告：采样率不匹配
            if (targetWavInfo.sampleRate != wavInfo.sampleRate) {
                android.util.Log.w("MainViewModel", "⚠️ Sample rate mismatch! Sample=${targetWavInfo.sampleRate} Hz, Offline=${wavInfo.sampleRate} Hz")
                android.util.Log.w("MainViewModel", "   MFCC features may not match correctly. Consider resampling files to the same rate.")
            }
        }

        // Load sample audio
        val targetSamples = com.example.audiometer.utils.WavUtil.loadWav(targetFile)
        if (targetSamples.isEmpty()) {
            android.util.Log.e("MainViewModel", "Failed to load sample audio")
            offlineResultMessage = "Could not load sample"
            return@withContext Pair(wavInfo, emptyList())
        }
        val targetFloats = FloatArray(targetSamples.size) { targetSamples[it].toFloat() }
        android.util.Log.d("MainViewModel", "Loaded sample: ${targetSamples.size} samples")

        // 2. Load Input File
        val inputSamples = com.example.audiometer.utils.WavUtil.loadWav(file)
        if (inputSamples.isEmpty()) {
            android.util.Log.e("MainViewModel", "Failed to load offline file")
            offlineResultMessage = "Could not load analysis file"
            return@withContext Pair(wavInfo, emptyList())
        }
        val inputFloats = FloatArray(inputSamples.size) { inputSamples[it].toFloat() }
        android.util.Log.d("MainViewModel", "Loaded offline file: ${inputSamples.size} samples")

        // 3. 使用 MFCCMatcher 进行检测（与测试逻辑完全一致）
        val euclideanThreshold = configRepo.similarityThreshold  // 直接使用欧氏距离阈值
        android.util.Log.d("MainViewModel", "Starting MFCC matching with threshold: $euclideanThreshold, sampleRate: $actualSampleRate")
        
        // 使用 MFCCMatcher.detectMatches（包含完整的去重逻辑）
        // ⚠️ 使用实际文件的采样率，而不是硬编码 16000
        val matchResults = com.example.audiometer.utils.MFCCMatcher.detectMatches(
            longAudio = inputFloats,
            sampleAudio = targetFloats,
            sampleRate = actualSampleRate,  // ✅ 使用实际采样率
            frameSize = com.example.audiometer.utils.MFCCMatcher.FRAME_SIZE,
            hopLength = com.example.audiometer.utils.MFCCMatcher.HOP_LENGTH,
            threshold = euclideanThreshold,  // 直接使用欧氏距离阈值
            onProgress = onProgress  // 传递进度回调
        )
        
        android.util.Log.d("MainViewModel", "Found ${matchResults.size} matches")
        
        // 转换为 OfflineAnalysisResult 格式
        for (match in matchResults) {
            val timeMs = (match.timeSeconds * 1000).toLong()
            
            // 验证时间戳是否在合理范围内
            if (timeMs > wavInfo.durationMs) {
                android.util.Log.w("MainViewModel", "⚠️ Match time ${timeMs}ms exceeds file duration ${wavInfo.durationMs}ms! Check sample rate.")
            }
            
            android.util.Log.d("MainViewModel", "Match at ${timeMs}ms (${match.timeSeconds}s), distance=${match.distance}")
            // distance 是实际欧氏距离值
            matches.add(OfflineAnalysisResult(timeMs, match.distance, euclideanThreshold))
        }

        offlineResults = matches
        offlineResultMessage = "Found ${matches.size} occurrences"

        Pair(wavInfo, matches)
    }
}
