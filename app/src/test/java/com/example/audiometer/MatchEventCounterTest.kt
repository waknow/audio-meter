package com.example.audiometer

import com.example.audiometer.utils.MatchEventCounter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchEventCounterTest {

    @Test
    fun shouldOnlyTriggerOnceForContinuousMatchRegion() {
        val counter = MatchEventCounter()
        val intervalMs = 1000L

        assertTrue(counter.shouldTrigger(isMatched = true, nowMs = 0L, minIntervalMs = intervalMs))
        assertFalse(counter.shouldTrigger(isMatched = true, nowMs = 500L, minIntervalMs = intervalMs))
        assertFalse(counter.shouldTrigger(isMatched = true, nowMs = 1500L, minIntervalMs = intervalMs))
    }

    @Test
    fun shouldTriggerAgainWhenMatchFallsAndRisesAfterInterval() {
        val counter = MatchEventCounter()
        val intervalMs = 1000L

        assertTrue(counter.shouldTrigger(isMatched = true, nowMs = 0L, minIntervalMs = intervalMs))
        assertFalse(counter.shouldTrigger(isMatched = false, nowMs = 200L, minIntervalMs = intervalMs))
        assertTrue(counter.shouldTrigger(isMatched = true, nowMs = 1500L, minIntervalMs = intervalMs))
    }

    @Test
    fun shouldNotTriggerWhenRiseHappensTooSoon() {
        val counter = MatchEventCounter()
        val intervalMs = 1000L

        assertTrue(counter.shouldTrigger(isMatched = true, nowMs = 0L, minIntervalMs = intervalMs))
        assertFalse(counter.shouldTrigger(isMatched = false, nowMs = 100L, minIntervalMs = intervalMs))
        assertFalse(counter.shouldTrigger(isMatched = true, nowMs = 500L, minIntervalMs = intervalMs))
    }

    @Test
    fun shouldRecoverWhenTimestampMovesBackward() {
        val counter = MatchEventCounter()
        val intervalMs = 1000L

        assertTrue(counter.shouldTrigger(isMatched = true, nowMs = 2000L, minIntervalMs = intervalMs))
        assertFalse(counter.shouldTrigger(isMatched = false, nowMs = 2100L, minIntervalMs = intervalMs))
        assertTrue(counter.shouldTrigger(isMatched = true, nowMs = 100L, minIntervalMs = intervalMs))
    }
}
