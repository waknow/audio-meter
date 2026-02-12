package com.example.audiometer.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AnalysisStateHolder {
    private val _currentSimilarity = MutableStateFlow(0f)
    val currentSimilarity: StateFlow<Float> = _currentSimilarity

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    // Stats
    private val _totalChecks = MutableStateFlow(0)
    val totalChecks: StateFlow<Int> = _totalChecks

    private val _matchCount = MutableStateFlow(0)
    val matchCount: StateFlow<Int> = _matchCount

    // Debug info
    private val _currentDistance = MutableStateFlow(0f)
    val currentDistance: StateFlow<Float> = _currentDistance

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel

    private val _lastProcessedTime = MutableStateFlow(0L)
    val lastProcessedTime: StateFlow<Long> = _lastProcessedTime

    fun updateSimilarity(similarity: Float, distance: Float = 0f, audioLevel: Float = 0f) {
        _currentSimilarity.value = similarity
        _currentDistance.value = distance
        _audioLevel.value = audioLevel
        _totalChecks.value += 1
        _lastProcessedTime.value = System.currentTimeMillis()
    }

    fun incrementMatchCount() {
        _matchCount.value += 1
    }

    fun resetStats() {
        _totalChecks.value = 0
        _matchCount.value = 0
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
        if (!running) {
            _currentSimilarity.value = 0f
            _currentDistance.value = 0f
            _audioLevel.value = 0f
        }
    }

    fun addLog(log: String) {
        val current = _logs.value.toMutableList()
        if (current.size > 100) current.removeAt(0)
        current.add(log)
        _logs.value = current
    }
}

