package com.example.audiometer.utils

import kotlin.math.sqrt

/**
 * MFCC 音频匹配工具（Python librosa 风格）
 * 用于离线分析和调试
 */
object MFCCMatcher {

    data class MatchResult(
        val frameIndex: Int,
        val distance: Float,
        val timeSeconds: Double
    )

    /**
     * 检测长音频中的所有匹配位置（Python 风格滑动窗口）
     * 
     * @param longAudio 长音频数据
     * @param sampleAudio 样本音频数据
     * @param sampleRate 采样率（建议使用 16000 与 Python 一致）
     * @param frameSize 帧大小（默认 1024）
     * @param hopLength 帧移（默认 256，Python 中的 hop_length）
     * @param threshold 距离阈值（默认 35.0，Python 中的值）
     * @param onProgress 进度回调（可选，范围 0.0-1.0）
     * @return 匹配结果列表
     */
    fun detectMatches(
        longAudio: FloatArray,
        sampleAudio: FloatArray,
        sampleRate: Float = 16000f,
        frameSize: Int = 2048,
        hopLength: Int = 512,
        threshold: Float = 35f,
        onProgress: ((Float) -> Unit)? = null
    ): List<MatchResult> {
        val targetSr = 16000f
        
        // 1. 如果采样率不是 16k，进行下采样（这是对齐 Python librosa 的关键，通常 librosa.load 会自动转换到 16k/22050）
        val resampledLong = if (sampleRate != targetSr) resample(longAudio, sampleRate, targetSr) else longAudio
        val resampledSample = if (sampleRate != targetSr) resample(sampleAudio, sampleRate, targetSr) else sampleAudio
        
        val extractor = AudioFeatureExtractor()
        
        // 2. 计算样本的 MFCC 序列（使用滑动窗口提取）
        val sampleMFCCs = extractMFCCSequence(resampledSample, frameSize, targetSr, extractor)
        
        if (sampleMFCCs.isEmpty()) {
            return emptyList()
        }
        
        // 改进：不再使用简单的平均值，而是寻找样本中能量最强的帧作为指纹
        // 这比包含静音边缘的平均值更鲁棒
        val bestSampleMFCC = findBestRepresentativeMFCC(resampledSample, frameSize, targetSr, extractor) ?: averageMFCC(sampleMFCCs)
        
        // 3. 滑动窗口扫描重采样后的长音频
        val matches = mutableListOf<MatchResult>()
        var pos = 0
        var frameIdx = 0
        var lastProgress = 0f
        
        while (pos + frameSize <= resampledLong.size) {
            // 更新进度
            val currentProgress = pos.toFloat() / resampledLong.size
            if (onProgress != null && currentProgress - lastProgress >= 0.05f) {
                onProgress(currentProgress)
                lastProgress = currentProgress
            }
            
            // 提取当前帧的 MFCC (使用 targetSr)
            val chunk = resampledLong.sliceArray(pos until pos + frameSize)
            val currentMFCC = extractor.calculateMFCC(chunk, targetSr)
            val currentMFCCWithoutC0 = currentMFCC.sliceArray(1 until currentMFCC.size)
            
            // 与样本的最强特征对比
            val distance = extractor.calculateEuclideanDistance(currentMFCCWithoutC0, bestSampleMFCC)
            
            if (distance < threshold) {
                val timeSeconds = (pos.toDouble() / targetSr)
                matches.add(MatchResult(frameIdx, distance, timeSeconds))
            }
            
            pos += hopLength
            frameIdx++
        }
        
        onProgress?.invoke(1.0f)
        
        // 合并相邻匹配（基于重采样后的帧移计算 maxGap）
        // 约一个样本长度内的匹配都视为同一个
        val maxGapFrames = (resampledSample.size / hopLength) + 2
        return mergeAdjacentMatches(matches, maxGap = maxGapFrames)
    }

    /**
     * 线性插值重采样
     */
    internal fun resample(data: FloatArray, fromSr: Float, toSr: Float): FloatArray {
        if (fromSr == toSr) return data
        val ratio = fromSr / toSr
        val newSize = (data.size / ratio).toInt()
        val result = FloatArray(newSize)
        for (i in 0 until newSize) {
            val center = i * ratio
            val left = center.toInt()
            val right = minOf(left + 1, data.size - 1)
            val frac = center - left
            result[i] = (1 - frac) * data[left] + frac * data[right]
        }
        return result
    }

    /**
     * 提取音频的 MFCC 序列（使用滑动窗口）
     */
    private fun extractMFCCSequence(
        audio: FloatArray,
        frameSize: Int,
        sampleRate: Float,
        extractor: AudioFeatureExtractor
    ): List<FloatArray> {
        val mfccs = mutableListOf<FloatArray>()
        var pos = 0
        val hop = frameSize / 4 // 样本提取也使用重叠，增加样本特征的稳定性
        
        while (pos + frameSize <= audio.size) {
            val chunk = audio.sliceArray(pos until pos + frameSize)
            val mfcc = extractor.calculateMFCC(chunk, sampleRate)
            // 删除 C0（与 Python 一致）
            val mfccWithoutC0 = mfcc.sliceArray(1 until mfcc.size)
            mfccs.add(mfccWithoutC0)
            pos += hop
        }

        // 如果音频太短不到一个 frameSize
        if (mfccs.isEmpty() && audio.isNotEmpty()) {
            val chunk = FloatArray(frameSize)
            System.arraycopy(audio, 0, chunk, 0, minOf(audio.size, frameSize))
            val mfcc = extractor.calculateMFCC(chunk, sampleRate)
            mfccs.add(mfcc.sliceArray(1 until mfcc.size))
        }
        
        return mfccs
    }

    /**
     * 计算 MFCC 序列的平均值
     */
    private fun averageMFCC(mfccs: List<FloatArray>): FloatArray {
        if (mfccs.isEmpty()) return FloatArray(12)
        
        val dim = mfccs[0].size
        val avg = FloatArray(dim)
        
        for (mfcc in mfccs) {
            for (i in 0 until dim) {
                avg[i] += mfcc[i]
            }
        }
        
        for (i in 0 until dim) {
            avg[i] /= mfccs.size.toFloat()
        }
        
        return avg
    }

    /**
     * 在样本中寻找能量最强的帧作为代表性特征
     */
    fun findBestRepresentativeMFCC(
        audio: FloatArray,
        frameSize: Int,
        sampleRate: Float,
        extractor: AudioFeatureExtractor
    ): FloatArray? {
        var maxEnergy = -1f
        var bestMFCC: FloatArray? = null
        var pos = 0
        val hop = frameSize / 4
        
        while (pos + frameSize <= audio.size) {
            val chunk = audio.sliceArray(pos until pos + frameSize)
            val energy = extractor.calculateEnergy(chunk)
            if (energy > maxEnergy) {
                maxEnergy = energy
                val mfcc = extractor.calculateMFCC(chunk, sampleRate)
                bestMFCC = mfcc.sliceArray(1 until mfcc.size)
            }
            pos += hop
        }
        return bestMFCC
    }

    /**
     * 计算欧氏距离（与 Python numpy.linalg.norm 一致）
     */
    fun calculateEuclideanDistance(vec1: FloatArray, vec2: FloatArray): Float {
        if (vec1.size != vec2.size) return Float.MAX_VALUE
        
        var sum = 0.0
        for (i in vec1.indices) {
            val diff = vec1[i] - vec2[i]
            sum += diff * diff
        }
        return sqrt(sum).toFloat()
    }

    /**
     * 合并相邻的匹配结果（去除连续帧）
     */
    private fun mergeAdjacentMatches(
        matches: List<MatchResult>,
        maxGap: Int = 5
    ): List<MatchResult> {
        if (matches.isEmpty()) return emptyList()
        
        val merged = mutableListOf<MatchResult>()
        var currentGroup = mutableListOf(matches[0])
        
        for (i in 1 until matches.size) {
            val prev = matches[i - 1]
            val curr = matches[i]
            
            if (curr.frameIndex - prev.frameIndex <= maxGap) {
                currentGroup.add(curr)
            } else {
                // 保存当前组的最佳匹配
                merged.add(currentGroup.minByOrNull { it.distance }!!)
                currentGroup = mutableListOf(curr)
            }
        }
        
        // 处理最后一组
        if (currentGroup.isNotEmpty()) {
            merged.add(currentGroup.minByOrNull { it.distance }!!)
        }
        
        return merged
    }

    /**
     * 打印匹配结果（用于调试）
     */
    fun printMatches(matches: List<MatchResult>, expectedCount: Int = 39) {
        println("=" * 60)
        println("🎯 MFCC 匹配结果")
        println("=" * 60)
        println("总匹配数: ${matches.size}")
        println("期望数量: $expectedCount")
        println("准确率: ${if (expectedCount > 0) "%.1f%%".format(matches.size.toFloat() / expectedCount * 100) else "N/A"}")
        println("-" * 60)
        
        matches.forEachIndexed { index, match ->
            println("#${index + 1} @ Frame ${match.frameIndex} (${String.format("%.2f", match.timeSeconds)}s) - 距离: ${String.format("%.2f", match.distance)}")
        }
        
        println("=" * 60)
        
        val status = when {
            matches.size == expectedCount -> "✅ 完美匹配"
            matches.size in (expectedCount - 2)..(expectedCount + 2) -> "⚠️ 接近目标（容差范围内）"
            else -> "❌ 差异较大，需要调整参数"
        }
        println("状态: $status")
    }

    private operator fun String.times(n: Int) = this.repeat(n)
}
