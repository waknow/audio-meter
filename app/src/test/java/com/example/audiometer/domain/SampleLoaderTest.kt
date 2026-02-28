package com.example.audiometer.domain

import com.example.audiometer.service.SampleLoader
import com.example.audiometer.util.AudioFeatureExtractor
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 单元测试：[SampleLoader]
 *
 * 测试无需音频文件即可覆盖的边界条件。
 * 涉及文件 IO 的完整路径由 [MFCCMatchingTest] 覆盖（集成级别）。
 */
class SampleLoaderTest {

    private val extractor = AudioFeatureExtractor()

    @Test
    fun `returns null for nonexistent file`() {
        val result = SampleLoader.load(File("/nonexistent/path_that_does_not_exist.wav"), extractor)
        assertNull("SampleLoader should return null for a missing file", result)
    }

    @Test
    fun `returns null for empty WAV file`() {
        val emptyFile = File.createTempFile("empty_test", ".wav").also {
            it.deleteOnExit()
            it.writeBytes(ByteArray(0))
        }
        val result = SampleLoader.load(emptyFile, extractor)
        assertNull("SampleLoader should return null for an empty file", result)
    }

    @Test
    fun `does not throw for file with only wav header but no pcm data`() {
        // Write a minimal 44-byte WAV header with 0 data bytes
        val headerFile = File.createTempFile("header_only", ".wav").also { it.deleteOnExit() }
        headerFile.writeBytes(buildMinimalWavHeader(0))
        // Should not throw; may return null
        val result = runCatching { SampleLoader.load(headerFile, extractor) }
        assert(result.isSuccess) { "SampleLoader must not throw on a header-only file" }
    }

    // ── 辅助工具 ──────────────────────────────────────────────────────────────

    /**
     * 构造一个最小 44 字节 RIFF WAV 头，data chunk 大小为 [dataSizeBytes]，采样率 16000 Hz。
     */
    private fun buildMinimalWavHeader(dataSizeBytes: Int): ByteArray {
        val header = ByteArray(44)
        fun writeInt(offset: Int, value: Int) {
            header[offset + 0] = (value and 0xff).toByte()
            header[offset + 1] = (value shr 8 and 0xff).toByte()
            header[offset + 2] = (value shr 16 and 0xff).toByte()
            header[offset + 3] = (value shr 24 and 0xff).toByte()
        }
        fun writeShort(offset: Int, value: Short) {
            header[offset + 0] = (value.toInt() and 0xff).toByte()
            header[offset + 1] = (value.toInt() shr 8 and 0xff).toByte()
        }
        // RIFF
        "RIFF".toByteArray().copyInto(header, 0)
        writeInt(4, 36 + dataSizeBytes)
        "WAVE".toByteArray().copyInto(header, 8)
        // fmt chunk
        "fmt ".toByteArray().copyInto(header, 12)
        writeInt(16, 16) // chunk size
        writeShort(20, 1) // PCM
        writeShort(22, 1) // mono
        writeInt(24, 16000) // sample rate
        writeInt(28, 32000) // byte rate
        writeShort(32, 2)  // block align
        writeShort(34, 16) // bits per sample
        // data chunk
        "data".toByteArray().copyInto(header, 36)
        writeInt(40, dataSizeBytes)
        return header
    }
}
