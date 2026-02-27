package com.example.audiometer

import com.example.audiometer.utils.MFCCMatcher
import com.example.audiometer.utils.WavUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BusinessLogicDatasetTest {

    private fun resolveProjectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: "."
        return if (userDir.endsWith("/app")) File(userDir).parentFile ?: File(userDir) else File(userDir)
    }

    private fun loadProjectAudioPair(): Pair<FloatArray, FloatArray>? {
        val root = resolveProjectRoot()
        val sampleFile = File(root, "sample.wav")
        val longFile = File(root, "long-39.wav")
        if (!sampleFile.exists() || !longFile.exists()) return null

        val sample = WavUtil.loadWav(sampleFile)
        val longAudio = WavUtil.loadWav(longFile)
        if (sample.isEmpty() || longAudio.isEmpty()) return null

        return Pair(
            FloatArray(sample.size) { sample[it].toFloat() },
            FloatArray(longAudio.size) { longAudio[it].toFloat() }
        )
    }

    @Test
    fun detectMatches_shouldFindExpectedOccurrencesOnProjectData() {
        val (sample, longAudio) = loadProjectAudioPair() ?: run {
            println("⚠️ sample.wav / long-39.wav not found, skip test")
            return
        }

        val matches = MFCCMatcher.detectMatches(
            longAudio = longAudio,
            sampleAudio = sample,
            sampleRate = 16000f,
            threshold = 35f
        )

        assertEquals("Expected 39 occurrences on bundled dataset", 39, matches.size)
    }

    @Test
    fun detectMatches_shouldKeepResultsOrderedAndWithinDuration() {
        val (sample, longAudio) = loadProjectAudioPair() ?: run {
            println("⚠️ sample.wav / long-39.wav not found, skip test")
            return
        }
        val durationSec = longAudio.size.toDouble() / 16000.0

        val matches = MFCCMatcher.detectMatches(
            longAudio = longAudio,
            sampleAudio = sample,
            sampleRate = 16000f,
            threshold = 35f
        )

        assertTrue(matches.zipWithNext().all { (a, b) -> b.frameIndex >= a.frameIndex })
        assertTrue(matches.all { it.timeSeconds in 0.0..durationSec })
    }

    @Test
    fun detectMatches_shouldBeMonotonicWhenThresholdRelaxes() {
        val (sample, longAudio) = loadProjectAudioPair() ?: run {
            println("⚠️ sample.wav / long-39.wav not found, skip test")
            return
        }

        val strict = MFCCMatcher.detectMatches(
            longAudio = longAudio,
            sampleAudio = sample,
            sampleRate = 16000f,
            threshold = 30f
        ).size
        val loose = MFCCMatcher.detectMatches(
            longAudio = longAudio,
            sampleAudio = sample,
            sampleRate = 16000f,
            threshold = 40f
        ).size

        assertTrue("Relaxed threshold should not reduce match count", loose >= strict)
    }
}
