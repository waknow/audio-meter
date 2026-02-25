package com.example.audiometer

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.audiometer.utils.AnalysisStateHolder
import com.example.audiometer.utils.AudioFeatureExtractor
import com.example.audiometer.utils.MFCCMatcher
import com.example.audiometer.utils.WavUtil
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

/**
 * 实时逻辑仿真器
 * 用于在不启动 Service 的情况下，使用本地文件模拟麦克风输入来验证实时分析引擎的性能。
 */
class RealTimeLogicSimulator(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val featureExtractor = AudioFeatureExtractor()
    private val configRepo = (context.applicationContext as com.example.audiometer.AudioMeterApplication).configRepository
    
    // 同步 AudioAnalysisService 的核心常数
    private val FRAME_SIZE = 1024
    private val HOP_LENGTH = 256
    private val SAMPLE_RATE = 16000

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
                    frameSize = FRAME_SIZE,
                    sampleRate = SAMPLE_RATE.toFloat(),
                    extractor = featureExtractor
                )
                
                if (bestSampleMFCC == null) {
                    AnalysisStateHolder.addLog("Simulation Error: Could not find best sample frame")
                    return@launch
                }
                AnalysisStateHolder.addLog("Sample Fingerprint Loaded: ${sampleFile.name}")

                // 2. 加载“模拟输入” (模拟从 AudioRecord 读取的流)
                val inputData = WavUtil.loadWav(inputAudioFile)
                AnalysisStateHolder.addLog("Input Audio Loaded: ${inputAudioFile.name} (${inputData.size} samples)")

                // 3. 模拟实时处理循环 (模拟 startAnalysis 内部的 while 循环)
                var offset = 0
                var lastMatchTime = 0L
                val threshold = configRepo.similarityThreshold
                val sampleIntervalMs = configRepo.sampleIntervalMs
                while (offset + FRAME_SIZE <= inputData.size) {
                    val floatChunk = FloatArray(FRAME_SIZE)
                    for (i in 0 until FRAME_SIZE) {
                        floatChunk[i] = inputData[offset + i].toFloat()
                    }

                    // 计算音频能量
                    val audioLevel = featureExtractor.calculateEnergy(floatChunk)
                    
                    // 计算 MFCC (跳过 C0)
                    val mfcc = featureExtractor.calculateMFCC(floatChunk, SAMPLE_RATE.toFloat())
                    val mfccWithoutC0 = mfcc.sliceArray(1 until mfcc.size)
                    
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
                    
                    // 模拟实时感：每处理一帧休眠一下 (16000Hz 下 256 samples 约为 16ms)
                    delay(16) 
                    
                    offset += HOP_LENGTH
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
