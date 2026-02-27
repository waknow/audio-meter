package com.example.audiometer.utils

/**
 * Real-time match event counter:
 * - 仅在「未匹配 -> 匹配」边沿触发一次
 * - 结合最小时间间隔避免短时间重复触发
 */
class MatchEventCounter {
    private var previousMatched = false
    private var lastTriggerTimeMs = Long.MIN_VALUE

    fun reset() {
        previousMatched = false
        lastTriggerTimeMs = Long.MIN_VALUE
    }

    fun shouldTrigger(isMatched: Boolean, nowMs: Long, minIntervalMs: Long): Boolean {
        if (!isMatched) {
            previousMatched = false
            return false
        }

        val safeIntervalMs = minIntervalMs.coerceAtLeast(0L)
        val intervalSatisfied = lastTriggerTimeMs == Long.MIN_VALUE || (nowMs - lastTriggerTimeMs) >= safeIntervalMs
        val triggered = !previousMatched && intervalSatisfied

        previousMatched = true
        if (triggered) {
            lastTriggerTimeMs = nowMs
        }
        return triggered
    }
}
