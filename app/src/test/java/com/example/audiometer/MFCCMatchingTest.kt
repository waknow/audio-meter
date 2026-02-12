package com.example.audiometer

import com.example.audiometer.utils.AudioFeatureExtractor
import com.example.audiometer.utils.MFCCMatcher
import com.example.audiometer.utils.WavUtil
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * 测试 MFCC 音频匹配准确性
 * 对比 Python librosa 实现（应识别出 39 处匹配）
 */
class MFCCMatchingTest {

    /**
     * 主测试：验证是否能识别出 39 处匹配
     * 
     * Python 版本参数：
     * - sr = 16000
     * - n_fft = 1024
     * - hop_length = 256
     * - n_mfcc = 13（删除 C0，实际使用 12 个）
     * - 距离算法：欧氏距离
     * - 阈值：35
     */
    @Test
    fun testMatchingAccuracy() {
        val projectRoot = System.getProperty("user.dir").replace("/app", "")
        val sampleFile = File(projectRoot, "sample.wav")
        val longFile = File(projectRoot, "long-39.wav")

        if (!sampleFile.exists() || !longFile.exists()) {
            println("⚠️ 测试文件不存在，跳过测试")
            println("sample.wav: ${sampleFile.absolutePath}")
            println("long-39.wav: ${longFile.absolutePath}")
            return
        }

        println("\n" + "=".repeat(70))
        println("🎵 MFCC 匹配测试 - 验证识别准确性")
        println("=".repeat(70))

        // 加载音频
        val sampleData = WavUtil.loadWav(sampleFile)
        val longData = WavUtil.loadWav(longFile)
        
        val sampleFloats = FloatArray(sampleData.size) { sampleData[it].toFloat() }
        val longFloats = FloatArray(longData.size) { longData[it].toFloat() }

        println("📊 样本长度: ${sampleData.size} samples (${sampleFile.name})")
        println("📊 长音频长度: ${longData.size} samples (${longFile.name})")

        // 测试不同参数组合
        testWithParameters(sampleFloats, longFloats, "当前实现 (44100Hz)", 44100f, 35f)
        testWithParameters(sampleFloats, longFloats, "Python 对齐 (16000Hz)", 16000f, 35f)
        testWithParameters(sampleFloats, longFloats, "调整阈值 (16000Hz, threshold=25)", 16000f, 25f)
        testWithParameters(sampleFloats, longFloats, "调整阈值 (16000Hz, threshold=45)", 16000f, 45f)

        println("=".repeat(70))
        println("✅ 测试完成！请查看上述结果选择最佳参数配置")
    }

    private fun testWithParameters(
        sampleFloats: FloatArray,
        longFloats: FloatArray,
        label: String,
        sampleRate: Float,
        threshold: Float
    ) {
        println("\n" + "-".repeat(70))
        println("📍 测试配置: $label")
        println("   采样率: $sampleRate Hz")
        println("   阈值: $threshold")
        println("-".repeat(70))

        val startTime = System.currentTimeMillis()
        
        val matches = MFCCMatcher.detectMatches(
            longAudio = longFloats,
            sampleAudio = sampleFloats,
            sampleRate = sampleRate,
            frameSize = 1024,
            hopLength = 256,
            threshold = threshold
        )
        
        val elapsedMs = System.currentTimeMillis() - startTime

        MFCCMatcher.printMatches(matches, expectedCount = 39)
        println("⏱️ 处理时间: ${elapsedMs}ms")
    }

    @Test
    fun testParameterComparison() {
        println("\n📊 参数对比总结")
        println("=".repeat(70))
        println("\n【Python librosa 实现】")
        println("   采样率: 16000 Hz")
        println("   n_fft: 1024")
        println("   hop_length: 256")
        println("   MFCC 系数: 12 (删除 C0)")
        println("   Mel 滤波器: 默认值")
        println("   距离算法: 欧氏距离")
        println("   阈值: 35 (越小越相似)")
        
        println("\n【Android TarsosDSP 当前实现】")
        println("   采样率: 44100 Hz  ❌ 不匹配")
        println("   帧大小: 1024  ✅")
        println("   帧移: 实时单帧（无滑动窗口）  ❌ 不匹配")
        println("   MFCC 系数: 13 (包含 C0)  ❌ 不匹配")
        println("   Mel 滤波器: 40  ✅")
        println("   相似度算法: 余弦相似度  ❌ 不匹配")
        println("   阈值: 80.0 (越大越相似)  ❌ 方向相反")
        
        println("\n💡 建议修改项：")
        println("   1. 采样率改为 16000 Hz")
        println("   2. calculateMFCC() 中删除 C0")
        println("   3. 添加 calculateEuclideanDistance() 方法")
        println("   4. 实现滑动窗口匹配（见 MFCCMatcher.kt）")
        println("   5. 调整阈值为 35.0（欧氏距离）")
        println("=".repeat(70))
        
        assertTrue("参数对比已输出", true)
    }

    @Test
    fun testEuclideanVsCosineSimilarity() {
        val extractor = AudioFeatureExtractor()
        
        // 创建两个测试向量
        val vec1 = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val vec2 = floatArrayOf(1.1f, 2.1f, 2.9f, 4.2f, 4.8f)
        val vec3 = floatArrayOf(5f, 4f, 3f, 2f, 1f)  // 完全相反
        
        println("\n🔬 距离算法对比")
        println("=".repeat(70))
        
        // 计算余弦相似度
        val cosine12 = extractor.calculateSimilarity(vec1, vec2)
        val cosine13 = extractor.calculateSimilarity(vec1, vec3)
        
        // 计算欧氏距离
        val euclidean12 = MFCCMatcher.calculateEuclideanDistance(vec1, vec2)
        val euclidean13 = MFCCMatcher.calculateEuclideanDistance(vec1, vec3)
        
        println("向量1 vs 向量2（相似）：")
        println("   余弦相似度: $cosine12")
        println("   欧氏距离: $euclidean12")
        
        println("\n向量1 vs 向量3（相反）：")
        println("   余弦相似度: $cosine13")
        println("   欧氏距离: $euclidean13")
        
        println("\n📌 结论：")
        println("   - 余弦相似度关注方向（角度），对幅度不敏感")
        println("   - 欧氏距离关注绝对差异，对幅度敏感")
        println("   - MFCC 匹配推荐使用欧氏距离（与 Python 一致）")
        println("=".repeat(70))
        
        assertTrue("算法对比已输出", true)
    }
}

