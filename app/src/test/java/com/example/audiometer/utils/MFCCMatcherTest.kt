package com.example.audiometer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MFCCMatcherTest {

    @Test
    fun `evaluateFeatures returns full similarity when vectors match`() {
        val sample = floatArrayOf(1f, 2f, 3f)
        val frame = floatArrayOf(1f, 2f, 3f)
        val audioLevel = 42f

        val result = MFCCMatcher.evaluateFeatures(frame, audioLevel, sample)

        assertEquals(0f, result.distance, 1e-6f)
        assertEquals(100f, result.similarity, 1e-6f)
        assertEquals(audioLevel, result.audioLevel, 1e-6f)
    }

    @Test
    fun `evaluateFeatures returns zero similarity when sample missing`() {
        val frame = floatArrayOf(1f, 2f, 3f)
        val audioLevel = 10f

        val result = MFCCMatcher.evaluateFeatures(frame, audioLevel, null)

        assertEquals(Float.MAX_VALUE, result.distance)
        assertEquals(0f, result.similarity, 1e-6f)
        assertEquals(audioLevel, result.audioLevel, 1e-6f)
        assertTrue(result.similarity in 0f..100f)
    }
}
