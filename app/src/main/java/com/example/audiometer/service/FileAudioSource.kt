package com.example.audiometer.service

import com.example.audiometer.util.MFCCMatcher
import com.example.audiometer.util.WavUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * 基于 WAV 文件的音频数据源。
 *
 * 自动将非 16 kHz 文件重采样到 [MFCCMatcher.SAMPLE_RATE]，
 * 以 [MFCCMatcher.HOP_LENGTH] 为步长滑动发射帧，帧结束后 Flow 自然终止。
 *
 * @param file WAV 文件。
 * @param realtimePacing true 时每帧发射后延迟，模拟实时麦克风速率（用于仿真调试）；
 *                       false 时全速处理（用于离线批量分析，但此情况更推荐直接使用 [MFCCMatcher.detectMatches]）。
 */
class FileAudioSource(
    private val file: File,
    private val realtimePacing: Boolean = true,
) : AudioSource {

    private var _totalSamples: Int? = null

    /** 重采样后的总样本数，在首次调用 [chunks] 并加载文件后才有值。 */
    override val totalSamples: Int? get() = _totalSamples

    override fun chunks(chunkSize: Int): Flow<FloatArray> = flow {
        val samples = WavUtil.loadWav(file)
        val wavInfo = WavUtil.getWavInfo(file)
        val fileRate = wavInfo?.sampleRate?.toFloat() ?: MFCCMatcher.SAMPLE_RATE.toFloat()
        val rawFloats = FloatArray(samples.size) { samples[it].toFloat() }

        val processed = if (fileRate != MFCCMatcher.SAMPLE_RATE.toFloat()) {
            MFCCMatcher.resample(rawFloats, fileRate, MFCCMatcher.SAMPLE_RATE.toFloat())
        } else {
            rawFloats
        }
        _totalSamples = processed.size

        var offset = 0
        while (offset + chunkSize <= processed.size) {
            emit(processed.sliceArray(offset until offset + chunkSize))
            if (realtimePacing) {
                delay(MFCCMatcher.HOP_LENGTH * 1000L / MFCCMatcher.SAMPLE_RATE)
            }
            offset += MFCCMatcher.HOP_LENGTH
        }
    }
}
