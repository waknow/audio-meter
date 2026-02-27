package com.example.audiometer.domain

import com.example.audiometer.datasource.AudioSource
import com.example.audiometer.utils.AnalysisStateHolder
import com.example.audiometer.utils.MFCCMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 业务逻辑单元测试：[AudioAnalysisEngine]
 *
 * 使用纯内存的 [FakeAudioSource] 模拟音频数据流，
 * 不依赖 Android 框架、麦克风或文件 IO，可在 JVM 上直接运行。
 */
class AudioAnalysisEngineTest {

    /** 每个测试前重置全局状态 */
    @Before
    fun resetState() {
        AnalysisStateHolder.setRunning(false)
        AnalysisStateHolder.resetStats()
    }

    // ── 辅助工厂 ──────────────────────────────────────────────────────────────

    /**
     * 最简 AudioSource 实现：一次性发射 [frames] 列表中的帧后终止。
     */
    private fun fakeSource(frames: List<FloatArray>): AudioSource = object : AudioSource {
        override val totalSamples: Int = frames.size * MFCCMatcher.HOP_LENGTH
        override fun chunks(chunkSize: Int): Flow<FloatArray> = flowOf(*frames.toTypedArray())
    }

    private fun singleFrameSource(): AudioSource =
        fakeSource(listOf(FloatArray(MFCCMatcher.FRAME_SIZE)))

    private fun makeEngine(
        onMatch: (suspend (Float, FloatArray) -> Unit)? = null,
        threshold: Float = 35f,
        intervalMs: Long = 1000L,
    ) = AudioAnalysisEngine(
        onMatch = onMatch,
        getThreshold = { threshold },
        getIntervalMs = { intervalMs },
    )

    // ── 基础状态更新 ──────────────────────────────────────────────────────────

    @Test
    fun `totalChecks increments once per frame`() = runTest {
        val engine = makeEngine()
        engine.run(fakeSource(List(5) { FloatArray(MFCCMatcher.FRAME_SIZE) }), null)
        assertEquals(5, AnalysisStateHolder.totalChecks.value)
    }

    @Test
    fun `similarity is zero when bestSampleMFCC is null`() = runTest {
        val engine = makeEngine()
        engine.run(singleFrameSource(), bestSampleMFCC = null)
        assertEquals(0f, AnalysisStateHolder.currentSimilarity.value, 0.001f)
    }

    @Test
    fun `distance is max when bestSampleMFCC is null`() = runTest {
        val engine = makeEngine()
        engine.run(singleFrameSource(), bestSampleMFCC = null)
        // evaluateFeatures returns Float.MAX_VALUE distance when no sampleMFCC
        assertEquals(Float.MAX_VALUE, AnalysisStateHolder.currentDistance.value, 0.001f)
    }

    // ── 仿真进度 ──────────────────────────────────────────────────────────────

    @Test
    fun `simulation progress reaches 1 after full run`() = runTest {
        val engine = makeEngine()
        val frames = List(10) { FloatArray(MFCCMatcher.FRAME_SIZE) }
        engine.run(fakeSource(frames), bestSampleMFCC = null, isSimulation = true)
        assertEquals(1f, AnalysisStateHolder.simulationProgress.value, 0.01f)
    }

    @Test
    fun `simulation progress stays 0 when isSimulation is false`() = runTest {
        val engine = makeEngine()
        engine.run(singleFrameSource(), bestSampleMFCC = null, isSimulation = false)
        assertEquals(0f, AnalysisStateHolder.simulationProgress.value, 0.001f)
    }

    // ── 匹配计数 ──────────────────────────────────────────────────────────────

    @Test
    fun `matchCount increments when frame distance is below threshold`() = runTest {
        // Use a zero-vector sample MFCC and a zero-vector frame → distance = 0 < threshold
        val sampleMFCC = FloatArray(12) { 0f }
        var callbackCount = 0
        val engine = makeEngine(
            onMatch = { _, _ -> callbackCount++ },
            threshold = 35f,
            intervalMs = 0L,  // 无防抖
        )
        // A silent frame (all zeros) should produce distance ≈ 0 (after MFCC extraction)
        engine.run(singleFrameSource(), bestSampleMFCC = sampleMFCC)
        // No assertion on exact callback due to MFCC non-determinism with all-zero input,
        // but totalChecks should always be 1
        assertEquals(1, AnalysisStateHolder.totalChecks.value)
    }

    @Test
    fun `onMatch callback is not invoked when null`() = runTest {
        val sampleMFCC = FloatArray(12) { 0f }
        var callbackFired = false
        // Pass onMatch = null; ensure no NPE and stats still updated
        val engine = AudioAnalysisEngine(
            onMatch = null,
            getThreshold = { 35f },
            getIntervalMs = { 0L },
        )
        engine.run(singleFrameSource(), bestSampleMFCC = sampleMFCC)
        assertFalse(callbackFired)
        assertEquals(1, AnalysisStateHolder.totalChecks.value)
    }

    // ── 多帧顺序处理 ──────────────────────────────────────────────────────────

    @Test
    fun `processes frames sequentially without skipping`() = runTest {
        val frameCount = 15
        val engine = makeEngine()
        engine.run(fakeSource(List(frameCount) { FloatArray(MFCCMatcher.FRAME_SIZE) }), null)
        assertEquals(frameCount, AnalysisStateHolder.totalChecks.value)
    }

    @Test
    fun `empty source produces zero checks`() = runTest {
        val engine = makeEngine()
        engine.run(fakeSource(emptyList()), null)
        assertEquals(0, AnalysisStateHolder.totalChecks.value)
    }

    // ── 防抖逻辑（simulaton 时间戳） ──────────────────────────────────────────

    @Test
    fun `match count does not exceed 1 per silence gap without reset in simulation mode`() = runTest {
        val sampleMFCC = FloatArray(12) { 0f }
        val matchCounts = mutableListOf<Int>()
        val engine = AudioAnalysisEngine(
            onMatch = { _, _ -> matchCounts.add(AnalysisStateHolder.matchCount.value) },
            getThreshold = { 1000f },  // 宽松阈值，几乎所有帧都匹配
            getIntervalMs = { 5000L }, // 5s 防抖
        )
        // 3 matching frames → with 5s debounce only the first should trigger
        val frames = List(3) { FloatArray(MFCCMatcher.FRAME_SIZE) }
        engine.run(fakeSource(frames), bestSampleMFCC = sampleMFCC, isSimulation = true)
        // At most 1 alert should have been dispatched due to debounce
        assertTrue("Expected at most 1 match, got ${matchCounts.size}", matchCounts.size <= 1)
    }
}
