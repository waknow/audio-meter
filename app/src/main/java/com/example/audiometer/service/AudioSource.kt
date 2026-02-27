package com.example.audiometer.service

import kotlinx.coroutines.flow.Flow

/**
 * 音频数据源接口，将麦克风与文件统一为相同的 PCM 帧流。
 *
 * 下游的 [AudioAnalysisEngine][com.example.audiometer.service.AudioAnalysisEngine] 通过此接口
 * 消费帧，与数据来源完全解耦，使得实时录音与文件仿真共用同一条处理链路。
 */
interface AudioSource {

    /**
     * 重采样到 16 kHz 后的总样本数量；实时麦克风时返回 null（未知）。
     * 用于计算并上报分析进度。
     */
    val totalSamples: Int?

    /**
     * 产生连续 PCM 帧的 [Flow]，每帧长度为 [chunkSize]（16 kHz，单声道，Float）。
     *
     * - **文件来源**：数据耗尽后 Flow 自然终止。
     * - **麦克风来源**：持续发射，直到收集方取消协程。
     *
     * 调用方负责在协程取消后释放底层资源（通过 Flow 的 `finally` 块保证）。
     */
    fun chunks(chunkSize: Int): Flow<FloatArray>
}
