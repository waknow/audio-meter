package com.example.audiometer.service

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.audiometer.util.MFCCMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.max

/**
 * 基于 [AudioRecord] 的实时麦克风音频数据源。
 *
 * 内部维护一个大小为 2×chunkSize 的滑动窗口缓冲区，从 [AudioRecord] 读入 PCM 短整型数据后，
 * 以 [MFCCMatcher.HOP_LENGTH] 为步长向前移动，每次凑满 [chunkSize] 时发射一帧 [FloatArray]。
 *
 * 麦克风权限（RECORD_AUDIO）需由调用方在启动前请求。
 * 协程取消时，`finally` 块会自动停止并释放 [AudioRecord]。
 */
class MicrophoneAudioSource : AudioSource {

    /** 实时麦克风无法预知总样本数，返回 null。 */
    override val totalSamples: Int? = null

    @SuppressLint("MissingPermission")
    override fun chunks(chunkSize: Int): Flow<FloatArray> = flow {
        val bufferSize = max(
            AudioRecord.getMinBufferSize(
                MFCCMatcher.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            chunkSize * 2
        )

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            MFCCMatcher.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        audioRecord.startRecording()

        val readBuffer = ShortArray(chunkSize)
        val slidingBuffer = ShortArray(chunkSize * 2)
        var slidingCount = 0

        try {
            while (true) {
                val read = audioRecord.read(readBuffer, 0, readBuffer.size)
                if (read > 0) {
                    for (i in 0 until read) {
                        if (slidingCount < slidingBuffer.size) {
                            slidingBuffer[slidingCount++] = readBuffer[i]
                        }
                    }
                    while (slidingCount >= chunkSize) {
                        emit(FloatArray(chunkSize) { slidingBuffer[it].toFloat() })
                        val remaining = slidingCount - MFCCMatcher.HOP_LENGTH
                        for (i in 0 until remaining) {
                            slidingBuffer[i] = slidingBuffer[i + MFCCMatcher.HOP_LENGTH]
                        }
                        slidingCount = remaining
                    }
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }
    }
}
