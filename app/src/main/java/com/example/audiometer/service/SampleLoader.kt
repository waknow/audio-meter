package com.example.audiometer.service

import com.example.audiometer.util.AudioFeatureExtractor
import com.example.audiometer.util.MFCCMatcher
import com.example.audiometer.util.WavUtil
import java.io.File

/**
 * 样本指纹：包含原始 MFCC 和 CMS 归一化后的 MFCC。
 *
 * - [bestMFCC]：能量最强帧的原始 MFCC（C1–C12），用于文件/仿真模式的直接比较。
 * - [cmsBestMFCC]：经倒谱均值减法（CMS）归一化后的 MFCC，用于实时麦克风模式，
 *   消除声学信道（扬声器→房间→麦克风）引入的静态频谱偏移。
 */
data class SampleFingerprint(
    val bestMFCC: FloatArray,
    val cmsBestMFCC: FloatArray,
)

/**
 * 从 WAV 样本文件中提取代表性 MFCC 指纹。
 *
 * Service 和 Simulator 均通过此类加载样本，确保采样率处理
 * 与特征提取方式完全一致，消除原有的代码重复。
 */
object SampleLoader {

    /**
     * 加载 [file] 并返回 [SampleFingerprint]，同时包含原始 MFCC 和 CMS 版本。
     *
     * @param file      样本 WAV 文件。
     * @param extractor [AudioFeatureExtractor] 实例（由调用方传入以便复用）。
     * @return 样本指纹，文件不存在、为空或抽帧失败时返回 null。
     */
    fun load(file: File, extractor: AudioFeatureExtractor): SampleFingerprint? {
        if (!file.exists()) return null
        return try {
            val samples = WavUtil.loadWav(file)
            if (samples.isEmpty()) return null

            val rawFloats = FloatArray(samples.size) { samples[it].toFloat() }
            val wavInfo = WavUtil.getWavInfo(file)
            val fileRate = wavInfo?.sampleRate?.toFloat() ?: MFCCMatcher.SAMPLE_RATE.toFloat()

            val processed = if (fileRate != MFCCMatcher.SAMPLE_RATE.toFloat()) {
                MFCCMatcher.resample(rawFloats, fileRate, MFCCMatcher.SAMPLE_RATE.toFloat())
            } else {
                rawFloats
            }

            val bestMFCC = MFCCMatcher.findBestRepresentativeMFCC(
                audio = processed,
                frameSize = MFCCMatcher.FRAME_SIZE,
                sampleRate = MFCCMatcher.SAMPLE_RATE.toFloat(),
                extractor = extractor
            ) ?: return null

            // 计算样本所有帧的平均 MFCC，用于 CMS 归一化
            val meanMFCC = MFCCMatcher.computeMeanMFCC(processed, extractor)
            val cmsBestMFCC = FloatArray(bestMFCC.size) { bestMFCC[it] - meanMFCC[it] }

            SampleFingerprint(bestMFCC = bestMFCC, cmsBestMFCC = cmsBestMFCC)
        } catch (e: Exception) {
            null   // 文件损坏、IO 异常等情况均返回 null，不向上层抛出
        }
    }
}
