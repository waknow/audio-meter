package com.example.audiometer

import android.content.Context
import com.example.audiometer.utils.AnalysisStateHolder
import com.example.audiometer.utils.AudioFeatureExtractor
import com.example.audiometer.utils.MFCCMatcher
import com.example.audiometer.utils.WavUtil
import kotlinx.coroutines.*
import java.io.File

/**
 * 实时逻辑仿真器
 * 用于在不启动 Service 的情况下，使用本地文件模拟麦克风输入来验证实时分析引擎的性能。
 */
class RealTimeLogicSimulator(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val featureExtractor = AudioFeatureExtractor()
    private val configRepo = (context.applicationContext as com.example.audiometer.AudioMeterApplication).configRepository

    fun simulate(sampleFile: File, inputAudioFile: File) {
        scope.launch {
            try {
                AnalysisStateHolder.resetStats()
                AnalysisStateHolder.addLog("--- SIMULATION START ---")

                // 1. 加载样本指纹 (模拟 loadSampleFingerprint)
                val sampleData = WavUtil.loadWav(sampleFile)
                val sampleFloats = FloatArray(sampleData.size) { sampleData[it].toFloat() }
                val bestSampleMFCC = MFCCMatcher.findBestRepresentativeMFCC(
                    audio = sampleFloats,
                    frameSize = MFCCMatcher.FRAME_SIZE,
                    sampleRate = MFCCMatcher.SAMPLE_RATE.toFloat(),
                    extractor = featureExtractor
                )

                if (bestSampleMFCC == null) {
                    AnalysisStateHolder.addLog("Simulation Error: Could not find best sample frame")
                    return@launch
                }
                AnalysisStateHolder.addLog("Sample Fingerprint Loaded: ${sampleFile.name}")

                // 2. 加载"模拟输入" (模拟从 AudioRecord 读取的流)
                val inputData = WavUtil.loadWav(inputAudioFile)
                AnalysisStateHolder.addLog("Input Audio Loaded: ${inputAudioFile.name} (${inputData.size} samples)")

                // 3. 模拟实时处理循环 (模拟 startAnalysis 内部的 while 循环)
                var offset = 0
                var lastMatchTime = 0L
                val threshold = configRepo.similarityThreshold
                val sampleIntervalMs = configRepo.sampleIntervalMs
                while (offset + MFCCMatcher.FRAME_SIZE <= inputData.size) {
                    val floatChunk = FloatArray(MFCCMatcher.FRAME_SIZE)
                    for (i in 0 until MFCCMatcher.FRAME_SIZE) {
                        floatChunk[i] = inputData[offset + i].toFloat()
                    }

                    // 计算音频能量
                    val audioLevel = featureExtractor.calculateEnergy(floatChunk)

                    // 通过 MFCCMatcher.extractFrameMFCC 统一提取 MFCC (C1..C12)
                    val mfccWithoutC0 = MFCCMatcher.extractFrameMFCC(floatChunk, featureExtractor)

                    // 计算距离
                    val distance = featureExtractor.calculateEuclideanDistance(mfccWithoutC0, bestSampleMFCC)

                    // 映射相似度 (逻辑与 AudioAnalysisService 严格一致)
                    val similarityPercentage = maxOf(0f, 100f - (distance / 70f * 100f))

                    // 更新 UI 状态
                    AnalysisStateHolder.updateSimilarity(similarityPercentage, distance, audioLevel)

                    // 记录匹配（与实时服务逻辑一致：防抖去重）
                    val currentTime = System.currentTimeMillis()
                    if (distance < threshold && currentTime - lastMatchTime > sampleIntervalMs) {
                        lastMatchTime = currentTime
                        AnalysisStateHolder.incrementMatchCount()
                    }

                    // 模拟实时感：每处理一帧休眠一下 (MFCCMatcher.HOP_LENGTH samples @ MFCCMatcher.SAMPLE_RATE ≈ 16ms)
                    delay(MFCCMatcher.HOP_LENGTH * 1000L / MFCCMatcher.SAMPLE_RATE)

                    offset += MFCCMatcher.HOP_LENGTH
                }

                AnalysisStateHolder.addLog("--- SIMULATION END ---")
            } catch (e: Exception) {
                AnalysisStateHolder.addLog("Simulation Failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        scope.cancel()
    }
}
