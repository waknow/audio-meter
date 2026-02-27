package com.example.audiometer.service

import com.example.audiometer.service.AudioSource
import com.example.audiometer.util.AnalysisStateHolder
import com.example.audiometer.util.AudioFeatureExtractor
import com.example.audiometer.util.MatchEventCounter
import com.example.audiometer.util.MFCCMatcher
import java.util.Locale

/**
 * 核心分析引擎：从任意 [AudioSource] 消费 PCM 帧，
 * 对每帧计算 MFCC 并与样本指纹比较，更新 [AnalysisStateHolder]，
 * 并通过 [onMatch] 回调分发告警事件。
 *
 * **设计关键**：实时麦克风（[MicrophoneAudioSource][com.example.audiometer.service.MicrophoneAudioSource]）
 * 与文件仿真（[FileAudioSource][com.example.audiometer.service.FileAudioSource]）
 * 共用相同的处理路径，单一处理逻辑，无重复代码。
 *
 * @param featureExtractor MFCC 提取器实例（可在调用方复用）。
 * @param onMatch 匹配事件回调；**null 表示仿真模式**——仅累计计数，不保存文件/写 DB/上传 HA。
 * @param getThreshold 返回当前欧氏距离阈值的函数（支持运行时变化）。
 * @param getIntervalMs 返回告警最小间隔（毫秒）的函数。
 */
class AudioAnalysisEngine(
    internal val featureExtractor: AudioFeatureExtractor = AudioFeatureExtractor(),
    private val onMatch: (suspend (similarity: Float, chunk: FloatArray) -> Unit)? = null,
    private val getThreshold: () -> Float,
    private val getIntervalMs: () -> Long,
) {

    /**
     * 启动分析循环，挂起直到 [source] 的 Flow 终止（文件结束）或收集协程被取消（停止录音）。
     *
     * @param source         音频数据源（麦克风或文件）。
     * @param bestSampleMFCC 已加载的样本代表性 MFCC；null 时所有帧距离为无穷大，相似度为 0。
     * @param isSimulation   true 时向 [AnalysisStateHolder] 上报仿真进度百分比。
     */
    suspend fun run(
        source: AudioSource,
        bestSampleMFCC: FloatArray?,
        isSimulation: Boolean = false,
    ) {
        val matchCounter = MatchEventCounter()
        val totalSamples = source.totalSamples
        var frameIndex = 0

        source.chunks(MFCCMatcher.FRAME_SIZE).collect { chunk ->
            val result = MFCCMatcher.evaluateFrame(chunk, bestSampleMFCC, featureExtractor)
            AnalysisStateHolder.updateSimilarity(result.similarity, result.distance, result.audioLevel)

            if (isSimulation && totalSamples != null && totalSamples > 0) {
                val progress = ((frameIndex + 1) * MFCCMatcher.HOP_LENGTH.toFloat() / totalSamples)
                    .coerceIn(0f, 1f)
                AnalysisStateHolder.updateSimulationProgress(progress)
            }

            if (frameIndex % 50 == 0) {
                AnalysisStateHolder.addLog(
                    "Frame #$frameIndex: Dist=${
                        String.format(Locale.US, "%.2f", result.distance)
                    }, Level=${String.format(Locale.US, "%.0f", result.audioLevel)}"
                )
            }

            // 仿真时使用音频文件内时间位置；实时录音使用系统时钟
            val nowMs = if (isSimulation) {
                frameIndex * MFCCMatcher.HOP_LENGTH * 1000L / MFCCMatcher.SAMPLE_RATE
            } else {
                System.currentTimeMillis()
            }

            if (matchCounter.shouldTrigger(
                    isMatched = result.distance < getThreshold(),
                    nowMs = nowMs,
                    minIntervalMs = getIntervalMs()
                )
            ) {
                AnalysisStateHolder.incrementMatchCount()
                onMatch?.invoke(result.similarity, chunk)
            }

            frameIndex++
        }

        if (isSimulation) {
            AnalysisStateHolder.updateSimulationProgress(1f)
        }
    }
}
