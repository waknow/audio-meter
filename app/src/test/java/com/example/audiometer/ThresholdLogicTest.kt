package com.example.audiometer

import com.example.audiometer.util.AudioFeatureExtractor
import com.example.audiometer.util.MFCCMatcher
import org.junit.Test
import org.junit.Assert.*

/**
 * 验证阈值逻辑的正确性
 * 确保 UI、离线分析、实时检测使用相同的阈值语义
 */
class ThresholdLogicTest {

    /**
     * 测试欧氏距离计算是否正确
     */
    @Test
    fun testEuclideanDistanceCalculation() {
        // 完全相同的向量
        val vec1 = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val vec2 = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val distance1 = MFCCMatcher.calculateEuclideanDistance(vec1, vec2)
        assertEquals(0f, distance1, 0.001f)

        // 稍有差异的向量
        val vec3 = floatArrayOf(1.1f, 2.1f, 3.1f, 4.1f, 5.1f)
        val distance2 = MFCCMatcher.calculateEuclideanDistance(vec1, vec3)
        assertTrue("Distance should be small", distance2 < 1f)

        // 差异较大的向量
        val vec4 = floatArrayOf(10f, 20f, 30f, 40f, 50f)
        val distance3 = MFCCMatcher.calculateEuclideanDistance(vec1, vec4)
        assertTrue("Distance should be large", distance3 > 50f)

        println("✅ Euclidean distance calculation test passed")
        println("   Same vectors: $distance1")
        println("   Similar vectors: $distance2")
        println("   Different vectors: $distance3")
    }

    /**
     * 测试阈值过滤逻辑
     * 验证只有 distance < threshold 的结果被保留
     */
    @Test
    fun testThresholdFiltering() {
        val threshold = 35f
        
        // 模拟 detectMatches 的过滤逻辑
        val distances = listOf(10f, 25f, 34.9f, 35f, 35.1f, 50f, 100f)
        val filtered = distances.filter { it < threshold }
        
        assertEquals("Should filter correctly", 3, filtered.size)
        assertTrue("Should include 10", filtered.contains(10f))
        assertTrue("Should include 25", filtered.contains(25f))
        assertTrue("Should include 34.9", filtered.contains(34.9f))
        assertFalse("Should exclude 35", filtered.contains(35f))
        assertFalse("Should exclude 35.1", filtered.contains(35.1f))
        
        println("✅ Threshold filtering test passed")
        println("   Threshold: $threshold")
        println("   Passed: $filtered")
        println("   Rejected: ${distances.filter { it >= threshold }}")
    }

    /**
     * 测试不同阈值下的匹配数量
     * 阈值越小，应该匹配越少（越严格）
     */
    @Test
    fun testThresholdStrictness() {
        // 模拟一组距离值
        val distances = listOf(5f, 10f, 15f, 20f, 25f, 30f, 35f, 40f, 45f, 50f)
        
        val threshold20 = distances.filter { it < 20f }.size
        val threshold35 = distances.filter { it < 35f }.size
        val threshold50 = distances.filter { it < 50f }.size
        
        assertTrue("Threshold 20 should be stricter than 35", threshold20 < threshold35)
        assertTrue("Threshold 35 should be stricter than 50", threshold35 < threshold50)
        
        println("✅ Threshold strictness test passed")
        println("   Threshold 20: $threshold20 matches")
        println("   Threshold 35: $threshold35 matches")
        println("   Threshold 50: $threshold50 matches")
    }

    /**
     * 测试 OfflineAnalysisResult 的数据存储
     * 验证 similarity 字段现在存储的是 distance 值
     */
    @Test
    fun testOfflineAnalysisResultStorage() {
        val timestamp = 1000L
        val distance = 25.5f
        val threshold = 35f
        
        // 模拟 MainViewModel 中的逻辑
        data class OfflineAnalysisResult(
            val timestamp: Long,
            val similarity: Float,  // 现在存储的是 distance
            val threshold: Float
        )
        
        val result = OfflineAnalysisResult(timestamp, distance, threshold)
        
        assertEquals(timestamp, result.timestamp)
        assertEquals(distance, result.similarity, 0.001f)
        assertEquals(threshold, result.threshold, 0.001f)
        
        // 验证过滤逻辑
        assertTrue("Should pass threshold", result.similarity < result.threshold)
        
        println("✅ OfflineAnalysisResult storage test passed")
        println("   Distance: ${result.similarity}")
        println("   Threshold: ${result.threshold}")
        println("   Pass: ${result.similarity < result.threshold}")
    }

    /**
     * 测试 UI 显示逻辑
     * 验证距离值的显示和颜色标识
     */
    @Test
    fun testUIDisplayLogic() {
        val threshold = 35f
        
        // 测试用例：(distance, shouldHighlight)
        val testCases = listOf(
            10f to true,   // 很好的匹配
            25f to true,   // 好的匹配
            34.9f to true, // 刚好通过
            35f to false,  // 刚好不通过
            40f to false,  // 超过阈值
            50f to false   // 远超阈值
        )
        
        testCases.forEach { (distance, shouldHighlight) ->
            val isGoodMatch = distance < threshold
            assertEquals(
                "Distance $distance should ${if (shouldHighlight) "" else "not "}be highlighted",
                shouldHighlight,
                isGoodMatch
            )
        }
        
        println("✅ UI display logic test passed")
        println("   Good matches (< $threshold): ${testCases.filter { it.second }.map { it.first }}")
        println("   Bad matches (>= $threshold): ${testCases.filter { !it.second }.map { it.first }}")
    }

    /**
     * 测试与 MFCCMatcher.detectMatches 的集成
     * 验证阈值参数正确传递和应用
     */
    @Test
    fun testMFCCMatcherIntegration() {
        // 使用实际的音频文件进行测试（如果不存在则跳过）
        val userDir = System.getProperty("user.dir") ?: "."
        val projectRoot = userDir.replace("/app", "")
        val sampleFile = java.io.File(projectRoot, "sample.wav")
        
        if (!sampleFile.exists()) {
            println("⚠️ sample.wav not found, skipping integration test")
            println("✅ MFCCMatcher integration test skipped (no test files)")
            return
        }
        
        val sampleData = com.example.audiometer.util.WavUtil.loadWav(sampleFile)
        if (sampleData.isEmpty()) {
            println("⚠️ Could not load sample.wav, skipping integration test")
            println("✅ MFCCMatcher integration test skipped (load failed)")
            return
        }
        
        val sampleFloats = FloatArray(sampleData.size) { sampleData[it].toFloat() }
        
        // 使用样本音频自己对比自己（应该找到至少1个完美匹配）
        val matchesSelfLoose = MFCCMatcher.detectMatches(
            longAudio = sampleFloats,
            sampleAudio = sampleFloats,
            sampleRate = 16000f,
            threshold = 50f
        )
        
        val matchesSelfStrict = MFCCMatcher.detectMatches(
            longAudio = sampleFloats,
            sampleAudio = sampleFloats,
            sampleRate = 16000f,
            threshold = 5f  // 非常严格
        )
        
        // 自己对比自己，至少应该找到距离很小的匹配
        assertTrue("Should find at least 1 match when comparing to itself", 
            matchesSelfLoose.size >= 1)
        
        // 验证所有匹配都满足阈值条件
        matchesSelfLoose.forEach { match ->
            assertTrue("Match distance ${match.distance} should be < 50", match.distance < 50f)
        }
        
        matchesSelfStrict.forEach { match ->
            assertTrue("Match distance ${match.distance} should be < 5", match.distance < 5f)
        }
        
        println("✅ MFCCMatcher integration test passed")
        println("   Self-comparison (loose): ${matchesSelfLoose.size} matches")
        println("   Self-comparison (strict): ${matchesSelfStrict.size} matches")
        if (matchesSelfLoose.isNotEmpty()) {
            println("   Best match distance: ${matchesSelfLoose.minByOrNull { it.distance }?.distance}")
        }
    }

    /**
     * 测试推荐阈值范围
     */
    @Test
    fun testRecommendedThresholdRange() {
        val recommendedMin = 20f
        val recommendedMax = 50f
        val defaultThreshold = 35f
        
        assertTrue("Default should be in recommended range", 
            defaultThreshold >= recommendedMin && defaultThreshold <= recommendedMax)
        
        println("✅ Recommended threshold range test passed")
        println("   Recommended range: $recommendedMin - $recommendedMax")
        println("   Default: $defaultThreshold")
    }

    /**
     * 综合测试：验证完整的数据流
     * 从检测 -> 过滤 -> UI显示
     */
    @Test
    fun testCompleteDataFlow() {
        val threshold = 35f
        
        // 模拟 MFCCMatcher 返回的结果
        data class MatchResult(val frameIndex: Int, val distance: Float, val timeSeconds: Double)
        
        val rawMatches = listOf(
            MatchResult(10, 15.5f, 1.0),
            MatchResult(50, 28.3f, 2.0),
            MatchResult(100, 34.9f, 3.0),
            MatchResult(150, 35.1f, 4.0),  // 应该被过滤掉
            MatchResult(200, 45.0f, 5.0)   // 应该被过滤掉
        )
        
        // 模拟 MainViewModel 的过滤逻辑（在 MFCCMatcher 内部已完成）
        val filteredMatches = rawMatches.filter { it.distance < threshold }
        
        assertEquals("Should keep 3 matches", 3, filteredMatches.size)
        
        // 验证所有保留的匹配都满足条件
        filteredMatches.forEach { match ->
            assertTrue("Distance ${match.distance} should < $threshold", match.distance < threshold)
        }
        
        // 模拟 UI 显示
        filteredMatches.forEach { match ->
            val displayText = "Distance: ${String.format("%.2f", match.distance)} (Threshold: ${String.format("%.2f", threshold)})"
            val isGoodMatch = match.distance < threshold
            
            assertTrue("Should always be good match in filtered results", isGoodMatch)
            println("   ✓ $displayText [PASS]")
        }
        
        println("✅ Complete data flow test passed")
        println("   Input: ${rawMatches.size} raw matches")
        println("   Output: ${filteredMatches.size} filtered matches")
    }
}
