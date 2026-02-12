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
    private val context = application.applicationContext
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
                    } catch (e: Exception) { /* ignore */ }
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
        val fftSize = 512

        // 1. Load Target Fingerprint
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

        // Load target
        val targetSamples = com.example.audiometer.utils.WavUtil.loadWav(targetFile)
        if (targetSamples.isEmpty()) {
            offlineResultMessage = "Could not load sample"
            return@withContext Pair(wavInfo, emptyList())
        }
        val targetFloats = FloatArray(targetSamples.size) { targetSamples[it].toFloat() }
        val targetFingerprint = extractor.computeAverageSpectrum(targetFloats, fftSize)

        // 2. Load Input File
        val inputSamples = com.example.audiometer.utils.WavUtil.loadWav(file)
        if (inputSamples.isEmpty()) {
            offlineResultMessage = "Could not load analysis file"
            return@withContext Pair(wavInfo, emptyList())
        }

        // 3. Sliding Window Analysis
        val step = fftSize
        var pos = 0
        val tempBuffer = FloatArray(fftSize)
        val threshold = configRepo.similarityThreshold
        val sampleRate = wavInfo?.sampleRate ?: 44100

        val totalSamples = inputSamples.size
        var lastProgress = 0f

        while (pos + fftSize <= totalSamples) {
            // Update progress every roughly 1%
            val currentProgress = pos.toFloat() / totalSamples
            if (currentProgress - lastProgress >= 0.01) {
                onProgress(currentProgress)
                lastProgress = currentProgress
            }

            // Convert to float
            for (i in 0 until fftSize) {
                tempBuffer[i] = inputSamples[pos + i].toFloat()
            }

            val spectrum = extractor.calculateFFT(tempBuffer)
            val similarity = extractor.calculateSimilarity(spectrum, targetFingerprint)

            if (similarity >= threshold) {
                // Determine time (in ms)
                val timeMs = (pos.toLong() * 1000) / sampleRate
                matches.add(OfflineAnalysisResult(timeMs, similarity, threshold))
                // Skip ahead to avoid multiple matches for same sound
                pos += sampleRate // Skip 1 sec
            } else {
                pos += step
            }
        }

        onProgress(1.0f)

        offlineResults = matches
        offlineResultMessage = "Found ${matches.size} occurrences"

        Pair(wavInfo, matches)
    }
}
