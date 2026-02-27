package com.example.audiometer.service

import android.content.Context
import com.example.audiometer.service.FileAudioSource
import com.example.audiometer.service.AudioAnalysisEngine
import com.example.audiometer.service.SampleLoader
import com.example.audiometer.util.AnalysisStateHolder
import com.example.audiometer.util.AudioFeatureExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 模拟实时分析流程，使用本地 WAV 文件替代麦克风输入。
 *
 * 与真实录音共用 [AudioAnalysisEngine]，确保处理逻辑完全一致。
 * 不触发告警副作用（不保存文件、不写 DB、不上传 HA）。
 */
class RealTimeLogicSimulator(context: Context) {
    private val configRepo = (context.applicationContext as AudioMeterApplication).configRepository
    private val featureExtractor = AudioFeatureExtractor()
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var simulationJob: Job? = null

    fun simulate(sampleFile: File, inputAudioFile: File) {
        simulationJob?.cancel()
        simulationJob = scope.launch {
            try {
                AnalysisStateHolder.resetStats()
                AnalysisStateHolder.addLog("--- SIMULATION START ---")

                val bestSampleMFCC = SampleLoader.load(sampleFile, featureExtractor)
                if (bestSampleMFCC == null) {
                    AnalysisStateHolder.addLog("Simulation Error: Could not find best sample frame")
                    return@launch
                }
                AnalysisStateHolder.addLog("Sample Fingerprint Loaded: ${sampleFile.name}")

                val engine = AudioAnalysisEngine(
                    featureExtractor = featureExtractor,
                    onMatch = null,   // 俯真模式：仅计数，不保存文件或上传 HA
                    getThreshold = { configRepo.similarityThreshold },
                    getIntervalMs = { configRepo.sampleIntervalMs },
                )

                engine.run(FileAudioSource(inputAudioFile, realtimePacing = true), bestSampleMFCC, isSimulation = true)

                AnalysisStateHolder.addLog("--- SIMULATION END ---")
                AnalysisStateHolder.setRunning(false)
            } catch (e: Exception) {
                AnalysisStateHolder.addLog("Simulation Failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        simulationJob?.cancel()
        simulationJob = null
        AnalysisStateHolder.setRunning(false)
    }
}
