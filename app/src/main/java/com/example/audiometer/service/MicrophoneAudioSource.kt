package com.example.audiometer.service

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.audiometer.util.AnalysisStateHolder
import com.example.audiometer.util.MFCCMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 基于 [AudioRecord] 的实时麦克风音频数据源。
 *
 * 使用 [MediaRecorder.AudioSource.VOICE_RECOGNITION] 禁用 AGC（自动增益控制）
 * 和噪声抑制，从而获取未经处理的原始 PCM 数据，使 MFCC 特征与离线 WAV 文件一致。
 *
 * 内部维护一个大小为 2×chunkSize 的滑动窗口缓冲区，从 [AudioRecord] 读入 PCM 短整型数据后，
 * 以 [MFCCMatcher.HOP_LENGTH] 为步长向前移动，每次凑满 [chunkSize] 时发射一帧 [FloatArray]。
 *
 * 麦克风权限（RECORD_AUDIO）需由调用方在启动前请求。
 * 协程取消时，`finally` 块会自动停止并释放 [AudioRecord]。
 */
class MicrophoneAudioSource : AudioSource {

    companion object {
        private const val TAG = "MicAudioSource"

        /**
         * RMS 能量低于此阈值的帧视为静音，跳过不发射。
         * 避免环境噪声经 RMS 归一化后产生随机 MFCC 干扰匹配。
         * 短整型 PCM 值域 -32768~32767，RMS 约 100 对应 -60 dBFS。
         */
        private const val SILENCE_RMS_THRESHOLD = 100f
    }

    /** 实时麦克风无法预知总样本数，返回 null。 */
    override val totalSamples: Int? = null

    @SuppressLint("MissingPermission")
    override fun chunks(chunkSize: Int): Flow<FloatArray> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            MFCCMatcher.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            val msg = "Device does not support ${MFCCMatcher.SAMPLE_RATE}Hz mono 16-bit recording"
            Log.e(TAG, msg)
            AnalysisStateHolder.addLog(msg)
            return@flow
        }
        val bufferSize = max(minBuf, chunkSize * 2)

        // 优先使用 VOICE_RECOGNITION（禁用 AGC/NS），失败时回退到 MIC
        val audioRecord = createAudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, bufferSize
        ) ?: createAudioRecord(
            MediaRecorder.AudioSource.MIC, bufferSize
        )

        if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            val msg = "AudioRecord initialization failed"
            Log.e(TAG, msg)
            AnalysisStateHolder.addLog(msg)
            audioRecord?.release()
            return@flow
        }

        val actualRate = audioRecord.sampleRate
        Log.i(TAG, "AudioRecord ready: rate=${actualRate}Hz, bufSize=$bufferSize")
        AnalysisStateHolder.addLog("Mic: ${actualRate}Hz, buf=$bufferSize")

        audioRecord.startRecording()

        val readBuffer = ShortArray(chunkSize)
        val slidingBuffer = ShortArray(chunkSize * 2)
        var slidingCount = 0
        var totalChunks = 0

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
                        val frame = FloatArray(chunkSize) { slidingBuffer[it].toFloat() }

                        val rms = computeRms(frame)
                        // 前 20 帧打印 RMS 诊断，确认静音过滤是否过于激进
                        if (totalChunks < 20) {
                            Log.d(TAG, "Chunk #$totalChunks: RMS=${"%.1f".format(rms)} -> ${if (rms >= SILENCE_RMS_THRESHOLD) "emit" else "skip"}")
                        }

                        if (rms >= SILENCE_RMS_THRESHOLD) {
                            emit(frame)
                        }
                        totalChunks++

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

    /** 尝试以指定 AudioSource 创建 AudioRecord，失败返回 null。 */
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(source: Int, bufferSize: Int): AudioRecord? {
        return try {
            val record = AudioRecord(
                source,
                MFCCMatcher.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                val sourceName = when (source) {
                    MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
                    MediaRecorder.AudioSource.MIC -> "MIC"
                    else -> "source=$source"
                }
                Log.i(TAG, "Created AudioRecord with $sourceName")
                AnalysisStateHolder.addLog("AudioSource: $sourceName")
                record
            } else {
                record.release()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create AudioRecord with source=$source", e)
            null
        }
    }

    private fun computeRms(frame: FloatArray): Float {
        var sumSq = 0f
        for (v in frame) sumSq += v * v
        return sqrt(sumSq / frame.size)
    }
}
