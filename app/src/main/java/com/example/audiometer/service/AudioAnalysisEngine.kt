package com.example.audiometer.service

import android.util.Log
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

    companion object {
        private const val TAG = "AudioEngine"
        /** CMS 预热帧数：累积足够帧后 CMS 均值才可靠 */
        private const val CMS_WARMUP_FRAMES = 50
        /** CMS 滑动窗口大小（约 3.2 秒 @16kHz/hop=256） */
        private const val CMS_WINDOW_SIZE = 200
    }

    /**
     * 启动分析循环，挂起直到 [source] 的 Flow 终止（文件结束）或收集协程被取消（停止录音）。
     *
     * 所有模式（实时麦克风 / 文件仿真 / 离线）**统一启用 CMS（倒谱均值减法）**，
     * 通过减去 MFCC 滑动窗口均值，消除各自录音信道的静态频谱偏移，
     * 使同一个距离阈值在所有场景下产生一致的匹配效果。
     *
     * @param source            音频数据源（麦克风或文件）。
     * @param sampleFingerprint 已加载的样本指纹；null 时所有帧距离为无穷大，相似度为 0。
     * @param isSimulation      true 时向 [AnalysisStateHolder] 上报仿真进度百分比。
     */
    suspend fun run(
        source: AudioSource,
        sampleFingerprint: SampleFingerprint?,
        isSimulation: Boolean = false,
    ) {
        val matchCounter = MatchEventCounter()
        val totalSamples = source.totalSamples
        var frameIndex = 0

        // 所有模式统一使用 CMS，确保同一阈值在实时/仿真/离线下效果一致
        val cmsBuffer = ArrayDeque<FloatArray>()

        source.chunks(MFCCMatcher.FRAME_SIZE).collect { chunk ->
            // 一帧只提取一次 MFCC，raw 和 CMS 路径共享
            val rawMFCC = MFCCMatcher.extractFrameMFCC(chunk, featureExtractor)
            val audioLevel = featureExtractor.calculateEnergy(chunk)

            // ── Raw 比较（诊断参考） ──
            val rawResult = MFCCMatcher.evaluateFeatures(
                rawMFCC, audioLevel, sampleFingerprint?.bestMFCC
            )

            // ── CMS 比较（统一用于所有模式的实际判断） ──
            val effectiveResult = run {
                cmsBuffer.addLast(rawMFCC.copyOf())
                if (cmsBuffer.size > CMS_WINDOW_SIZE) cmsBuffer.removeFirst()

                if (cmsBuffer.size >= CMS_WARMUP_FRAMES) {
                    // 计算滑动窗口均值
                    val cmsMean = FloatArray(rawMFCC.size)
                    for (mfcc in cmsBuffer) {
                        for (i in mfcc.indices) cmsMean[i] += mfcc[i]
                    }
                    for (i in cmsMean.indices) cmsMean[i] /= cmsBuffer.size
                    // 减去均值 → 消除信道静态频谱偏移
                    val cmsNormalized = FloatArray(rawMFCC.size) { rawMFCC[it] - cmsMean[it] }
                    MFCCMatcher.evaluateFeatures(
                        cmsNormalized, audioLevel, sampleFingerprint?.cmsBestMFCC
                    )
                } else {
                    rawResult // 预热阶段：CMS 均值不稳定，退回 raw
                }
            }

            AnalysisStateHolder.updateSimilarity(
                effectiveResult.similarity, effectiveResult.distance, effectiveResult.audioLevel
            )

            if (isSimulation && totalSamples != null && totalSamples > 0) {
                val progress = ((frameIndex + 1) * MFCCMatcher.HOP_LENGTH.toFloat() / totalSamples)
                    .coerceIn(0f, 1f)
                AnalysisStateHolder.updateSimulationProgress(progress)
            }

            // 前 10 帧密集打印，之后每 50 帧一次
            val logInterval = if (frameIndex < 10) 1 else 50
            if (frameIndex % logInterval == 0) {
                val msg = buildString {
                    append("Frame #$frameIndex: ")
                    append("Raw=${String.format(Locale.US, "%.1f", rawResult.distance)}")
                    if (cmsBuffer.size >= CMS_WARMUP_FRAMES) {
                        append(", CMS=${String.format(Locale.US, "%.1f", effectiveResult.distance)}")
                    }
                    append(", Sim=${String.format(Locale.US, "%.1f", effectiveResult.similarity)}%")
                    append(", Lv=${String.format(Locale.US, "%.0f", audioLevel)}")
                }
                AnalysisStateHolder.addLog(msg)
                Log.d(TAG, msg)
            }

            // 仿真时使用音频文件内时间位置；实时录音使用系统时钟
            val nowMs = if (isSimulation) {
                frameIndex * MFCCMatcher.HOP_LENGTH * 1000L / MFCCMatcher.SAMPLE_RATE
            } else {
                System.currentTimeMillis()
            }

            if (matchCounter.shouldTrigger(
                    isMatched = effectiveResult.distance < getThreshold(),
                    nowMs = nowMs,
                    minIntervalMs = getIntervalMs()
                )
            ) {
                AnalysisStateHolder.incrementMatchCount()
                onMatch?.invoke(effectiveResult.similarity, chunk)
            }

            frameIndex++
        }

        if (isSimulation) {
            AnalysisStateHolder.updateSimulationProgress(1f)
        }
    }
}
