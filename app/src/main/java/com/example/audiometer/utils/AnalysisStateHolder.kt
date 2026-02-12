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

    fun updateSimilarity(similarity: Float) {
        _currentSimilarity.value = similarity
        _totalChecks.value += 1
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun addLog(log: String) {
        val current = _logs.value.toMutableList()
        if (current.size > 100) current.removeAt(0)
        current.add(log)
        _logs.value = current
    }
}

