package com.example.audiometer.service

import com.example.audiometer.util.AudioFeatureExtractor
import com.example.audiometer.util.MFCCMatcher
import com.example.audiometer.util.WavUtil
import java.io.File

/**
 * 从 WAV 样本文件中提取代表性 MFCC 指纹。
 *
 * Service 和 Simulator 均通过此类加载样本，确保采样率处理
 * 与特征提取方式完全一致，消除原有的代码重复。
 */
object SampleLoader {

    /**
     * 加载 [file] 并返回能量最强帧的 MFCC 向量（已去除 C0，长度为 12）。
     *
     * @param file      样本 WAV 文件。
     * @param extractor [AudioFeatureExtractor] 实例（由调用方传入以便复用）。
     * @return MFCC 指纹，文件不存在、为空或抽帧失败时返回 null。
     */
    fun load(file: File, extractor: AudioFeatureExtractor): FloatArray? {
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

            MFCCMatcher.findBestRepresentativeMFCC(
                audio = processed,
                frameSize = MFCCMatcher.FRAME_SIZE,
                sampleRate = MFCCMatcher.SAMPLE_RATE.toFloat(),
                extractor = extractor
            )
        } catch (e: Exception) {
            null   // 文件损坏、IO 异常等情况均返回 null，不向上层抛出
        }
    }
}
