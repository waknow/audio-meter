package com.example.audiometer

import com.example.audiometer.util.MFCCMatcher
import com.example.audiometer.util.WavUtil
import org.junit.Test
import java.io.File

/**
 * 阈值扫描测试：引入 CMS 后，扫描不同阈值下 long-39.wav 的匹配数量，
 * 找到能准确识别 39 次特征的阈值范围。
 */
class CmsThresholdSweepTest {

    companion object {
        private const val EXPECTED = 39
    }

    private fun resolveProjectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: "."
        return if (userDir.endsWith("/app")) File(userDir).parentFile ?: File(userDir) else File(userDir)
    }

    private fun loadAudioPair(): Pair<FloatArray, FloatArray>? {
        val root = resolveProjectRoot()
        val sampleFile = File(root, "sample.wav")
        val longFile = File(root, "long-39.wav")
        if (!sampleFile.exists() || !longFile.exists()) {
            println("⚠️ sample.wav / long-39.wav not found at ${root.absolutePath}, skip")
            return null
        }
        val sample = WavUtil.loadWav(sampleFile)
        val longAudio = WavUtil.loadWav(longFile)
        if (sample.isEmpty() || longAudio.isEmpty()) return null
        return Pair(
            FloatArray(sample.size) { sample[it].toFloat() },
            FloatArray(longAudio.size) { longAudio[it].toFloat() }
        )
    }

    @Test
    fun sweepThresholds() {
        val (sample, longAudio) = loadAudioPair() ?: return

        // 从 5 到 80，步长 5 的粗扫描
        val coarseThresholds = (5..80 step 5).map { it.toFloat() }
        println("=" .repeat(70))
        println("CMS 阈值粗扫描 (long-39.wav, 期望 $EXPECTED 次匹配)")
        println("=" .repeat(70))
        println(String.format("%-12s %-12s %-12s %s", "阈值", "匹配数", "差值", "状态"))
        println("-".repeat(70))

        var bestThreshold = -1f
        var bestDiff = Int.MAX_VALUE

        for (threshold in coarseThresholds) {
            val matches = MFCCMatcher.detectMatches(
                longAudio = longAudio,
                sampleAudio = sample,
                sampleRate = 16000f,
                threshold = threshold
            )
            val diff = matches.size - EXPECTED
            val status = when {
                diff == 0 -> "✅ 完美"
                diff in -2..2 -> "⚠️ 接近"
                else -> "❌"
            }
            println(String.format("%-12.1f %-12d %-12d %s", threshold, matches.size, diff, status))

            if (kotlin.math.abs(diff) < bestDiff) {
                bestDiff = kotlin.math.abs(diff)
                bestThreshold = threshold
            }
        }

        println("\n粗扫描最佳阈值: $bestThreshold (差值: $bestDiff)")

        // 在最佳阈值 ±10 范围内进行细扫描（步长 1）
        val fineStart = maxOf(1f, bestThreshold - 10f)
        val fineEnd = bestThreshold + 10f
        println("\n" + "=".repeat(70))
        println("CMS 阈值细扫描 (${fineStart.toInt()} ~ ${fineEnd.toInt()})")
        println("=".repeat(70))
        println(String.format("%-12s %-12s %-12s %s", "阈值", "匹配数", "差值", "状态"))
        println("-".repeat(70))

        val perfectThresholds = mutableListOf<Float>()

        var t = fineStart
        while (t <= fineEnd) {
            val matches = MFCCMatcher.detectMatches(
                longAudio = longAudio,
                sampleAudio = sample,
                sampleRate = 16000f,
                threshold = t
            )
            val diff = matches.size - EXPECTED
            val status = when {
                diff == 0 -> "✅ 完美"
                diff in -2..2 -> "⚠️ 接近"
                else -> "❌"
            }
            println(String.format("%-12.1f %-12d %-12d %s", t, matches.size, diff, status))
            if (diff == 0) perfectThresholds.add(t)
            t += 1f
        }

        println("\n" + "=".repeat(70))
        if (perfectThresholds.isNotEmpty()) {
            println("✅ 能精确命中 $EXPECTED 次的阈值: $perfectThresholds")
            println("   推荐阈值: ${perfectThresholds[perfectThresholds.size / 2]}")
        } else {
            println("❌ 在扫描范围内未找到精确命中 $EXPECTED 次的阈值")
        }
        println("=".repeat(70))

        // 对推荐阈值输出详细匹配信息
        if (perfectThresholds.isNotEmpty()) {
            val recommended = perfectThresholds[perfectThresholds.size / 2]
            println("\n推荐阈值 $recommended 的详细匹配:")
            val matches = MFCCMatcher.detectMatches(
                longAudio = longAudio,
                sampleAudio = sample,
                sampleRate = 16000f,
                threshold = recommended
            )
            MFCCMatcher.printMatches(matches, EXPECTED)
        }

        // ── 超精细扫描：34~35 区间，步长 0.1 ──
        println("\n" + "=".repeat(70))
        println("超精细扫描 (34.0 ~ 35.0, 步长 0.1)")
        println("=".repeat(70))
        println(String.format("%-12s %-12s %-12s %s", "阈值", "匹配数", "差值", "状态"))
        println("-".repeat(70))
        var tf = 34.0f
        while (tf <= 35.05f) {
            val m = MFCCMatcher.detectMatches(
                longAudio = longAudio,
                sampleAudio = sample,
                sampleRate = 16000f,
                threshold = tf
            )
            val diff = m.size - EXPECTED
            val status = when {
                diff == 0 -> "✅ 完美"
                diff in -2..2 -> "⚠️ 接近"
                else -> "❌"
            }
            println(String.format("%-12.1f %-12d %-12d %s", tf, m.size, diff, status))
            if (diff == 0) {
                println("\n🎯 命中阈值 $tf 的详细匹配:")
                MFCCMatcher.printMatches(m, EXPECTED)
            }
            tf += 0.1f
        }
    }
}
