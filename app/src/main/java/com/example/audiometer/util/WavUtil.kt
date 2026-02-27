package com.example.audiometer.util

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
        if (!file.exists() || file.length() < 12) return null

        try {
            // Read first 2KB to find chunks (usually enough)
            val headerBytes = file.inputStream().use { it.readNBytes(2048) }
            if (headerBytes.size < 12) return null

            val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Check RIFF and WAVE
            if (headerBytes[0] != 'R'.code.toByte() || headerBytes[8] != 'W'.code.toByte()) {
                android.util.Log.e("WavUtil", "Not a valid RIFF/WAVE file: ${file.name}")
                return null
            }

            var sampleRate = 0
            var channels = 0
            var bitDepth = 0
            var dataSize = 0
            var fmtFound = false

            var offset = 12
            while (offset + 8 <= headerBytes.size) {
                // 读取 chunkId 字符串而不创建中间 byte 数组，避免编码问题
                val id1 = headerBytes[offset].toInt().toChar()
                val id2 = headerBytes[offset + 1].toInt().toChar()
                val id3 = headerBytes[offset + 2].toInt().toChar()
                val id4 = headerBytes[offset + 3].toInt().toChar()
                val chunkId = "$id1$id2$id3$id4"
                
                val chunkSize = buffer.getInt(offset + 4)
                
                if (chunkId == "fmt ") {
                    // fmt 块内部结构：
                    // +0: AudioFormat (2 bytes)
                    // +2: NumChannels (2 bytes)
                    // +4: SampleRate (4 bytes)
                    // +8: ByteRate (4 bytes)
                    // +12: BlockAlign (2 bytes)
                    // +14: BitsPerSample (2 bytes)
                    channels = buffer.getShort(offset + 10).toInt()
                    sampleRate = buffer.getInt(offset + 12)
                    bitDepth = buffer.getShort(offset + 22).toInt()
                    fmtFound = true
                } else if (chunkId == "data") {
                    dataSize = chunkSize
                    // 找到 data 块后可以停止扫描元数据
                    break
                }
                
                offset += 8 + chunkSize
                // 如果 chunkSize 是奇数，WAV 格式通常有一个填充字节
                if (chunkSize % 2 != 0) offset++
                
                // 防止死循环
                if (chunkSize <= 0 && chunkId != "data") break 
            }

            if (!fmtFound) {
                println("WavUtil: No 'fmt ' chunk found in ${file.name}")
                return null
            }

            // Calculate duration
            // Use file size to estimate if dataSize from header looks wrong
            val effectiveDataSize = if (dataSize > 0 && dataSize < file.length()) dataSize.toLong() 
                                   else file.length() - offset - 8
            
            val byteRate = sampleRate * channels * bitDepth / 8
            val durationMs = if (byteRate > 0) {
                (effectiveDataSize * 1000) / byteRate
            } else {
                0L
            }

            println("WavUtil: Parsed ${file.name}: ${sampleRate}Hz, ${channels}ch, ${durationMs}ms")
            return WavInfo(sampleRate, channels, bitDepth, durationMs)

        } catch (e: Exception) {
            println("WavUtil: Error parsing ${file.name}: ${e.message}")
            return null
        }
    }

    @Throws(IOException::class)
    fun loadWav(file: File): ShortArray {
        val bytes = file.readBytes()
        if (bytes.size < 12) return ShortArray(0)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        
        // Find data chunk
        var offset = 12
        var dataStart = -1
        var dataSize = 0

        while (offset + 8 <= bytes.size) {
            val id1 = bytes[offset].toInt().toChar()
            val id2 = bytes[offset + 1].toInt().toChar()
            val id3 = bytes[offset + 2].toInt().toChar()
            val id4 = bytes[offset + 3].toInt().toChar()
            val chunkId = "$id1$id2$id3$id4"
            
            val chunkSize = buffer.getInt(offset + 4)
            
            if (chunkId == "data") {
                dataStart = offset + 8
                dataSize = chunkSize
                break
            }
            offset += 8 + chunkSize
            if (chunkSize % 2 != 0) offset++
            
            if (chunkSize <= 0) break
        }

        if (dataStart == -1 || dataStart >= bytes.size) {
            android.util.Log.e("WavUtil", "No 'data' chunk found in ${file.name}")
            // Fallback: search for 'data' string manually
            for (i in 12 until bytes.size - 4) {
                if (bytes[i] == 'd'.code.toByte() && bytes[i+1] == 'a'.code.toByte() &&
                    bytes[i+2] == 't'.code.toByte() && bytes[i+3] == 'a'.code.toByte()) {
                    dataStart = i + 8
                    dataSize = bytes.size - dataStart
                    break
                }
            }
            if (dataStart == -1) return ShortArray(0)
        }

        val availableData = bytes.size - dataStart
        val actualDataSize = if (dataSize > 0 && dataSize <= availableData) dataSize else availableData
        
        val shortCount = actualDataSize / 2
        val shorts = ShortArray(shortCount)

        buffer.position(dataStart)
        for (i in 0 until shortCount) {
            if (buffer.remaining() >= 2) {
                shorts[i] = buffer.getShort()
            }
        }
        return shorts
    }
}
