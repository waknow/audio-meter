package com.example.audiometer.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val durationMs: Long
)

object WavUtil {
    @Throws(IOException::class)
    fun saveWav(
        file: File,
        pcmData: ShortArray,
        sampleRate: Int = 44100,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalAudioLen = pcmData.size * 2L
        val totalDataLen = totalAudioLen + 36

        FileOutputStream(file).use { fos ->
            writeWavHeader(fos, totalAudioLen, totalDataLen, sampleRate, channels, byteRate, bitsPerSample)
            val buffer = ByteBuffer.allocate(pcmData.size * 2)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcmData) {
                buffer.putShort(s)
            }
            fos.write(buffer.array())
        }
    }

    private fun writeWavHeader(
        out: FileOutputStream,
        totalAudioLen: Long,
        totalDataLen: Long,
        longSampleRate: Int,
        channels: Int,
        byteRate: Int,
        bitsPerSample: Int
    ) {
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte() // RIFF/WAVE header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = (longSampleRate shr 8 and 0xff).toByte()
        header[26] = (longSampleRate shr 16 and 0xff).toByte()
        header[27] = (longSampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte() // block align
        header[33] = 0
        header[34] = bitsPerSample.toByte() // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        out.write(header, 0, 44)
    }

    fun getWavInfo(file: File): WavInfo? {
        if (!file.exists() || file.length() < 44) return null

        try {
            file.inputStream().use { input ->
                val header = ByteArray(44)
                if (input.read(header) != 44) return null

                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

                // Validate RIFF and WAVE
                if (header[0] != 'R'.code.toByte() || header[8] != 'W'.code.toByte()) return null

                val channels = buffer.getShort(22).toInt()
                val sampleRate = buffer.getInt(24)
                val bitDepth = buffer.getShort(34).toInt()

                // Calculate duration
                val byteRate = buffer.getInt(28)
                val dataSize = buffer.getInt(40)

                // If byteRate is 0 or dataSize is 0, try to estimate
                val fileSizeData = file.length() - 44
                val effectiveByteRate = if (byteRate > 0) byteRate else sampleRate * channels * bitDepth / 8

                val durationMs = if (effectiveByteRate > 0) {
                     (fileSizeData * 1000) / effectiveByteRate
                } else {
                    0L
                }

                return WavInfo(sampleRate, channels, bitDepth, durationMs)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    @Throws(IOException::class)
    fun loadWav(file: File): ShortArray {
        // Very basic WAV loader, assumes 16-bit mono or takes first channel
        val bytes = file.readBytes()
        if (bytes.size < 44) return ShortArray(0)

        // Skip header - in production code we should parse it to get sample rate/channels
        // Here we assume it matches what we record/save for simplicity
        val dataSize = bytes.size - 44
        val shortCount = dataSize / 2
        val shorts = ShortArray(shortCount)

        val buffer = ByteBuffer.wrap(bytes, 44, dataSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shortCount) {
            shorts[i] = buffer.getShort()
        }
        return shorts
    }
}
