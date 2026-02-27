package com.example.audiometer.utils

/**
 * Real-time match event counter:
 * - Triggers only once on a non-match -> match edge transition
 * - Enforces a minimum interval to prevent short-term duplicate triggers
 */
class MatchEventCounter {
    private var previousMatched = false
    private var lastTriggerTimeMs = Long.MIN_VALUE

    fun reset() {
        previousMatched = false
        lastTriggerTimeMs = Long.MIN_VALUE
    }

    /**
     * Evaluates whether a new match event should be emitted for current frame.
     *
     * @param isMatched true when current frame distance passes threshold
     * @param nowMs current timestamp in milliseconds
     * @param minIntervalMs minimum allowed interval between two emitted events
     * @return true when a new event is accepted; otherwise false
     */
    fun shouldTrigger(isMatched: Boolean, nowMs: Long, minIntervalMs: Long): Boolean {
        if (!isMatched) {
            previousMatched = false
            return false
        }

        // Handles clock rewind / simulation timestamp reset between runs.
        if (lastTriggerTimeMs != Long.MIN_VALUE && nowMs < lastTriggerTimeMs) {
            lastTriggerTimeMs = Long.MIN_VALUE
            previousMatched = false
        }

        val intervalSatisfied = lastTriggerTimeMs == Long.MIN_VALUE || (nowMs - lastTriggerTimeMs) >= minIntervalMs
        val triggered = !previousMatched && intervalSatisfied

        previousMatched = true
        if (triggered) {
            lastTriggerTimeMs = nowMs
        }
        return triggered
    }
}
