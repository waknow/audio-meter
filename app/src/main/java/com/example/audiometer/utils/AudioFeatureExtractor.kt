package com.example.audiometer.utils

import be.tarsos.dsp.mfcc.MFCC
import kotlin.math.max
import kotlin.math.sqrt

class AudioFeatureExtractor {

    /**
     * Calculates MFCC features for a given audio buffer.
     * Includes energy normalization and Hanning window.
     */
    fun calculateMFCC(signal: FloatArray, sampleRate: Float = 16000f, skipNormalization: Boolean = false): FloatArray {
        val frame = signal.copyOf()
        
        // 1. 能量归一化 (关键：解决不同设备/距离导致的音量差异)
        // 将帧归一化到单位 RMS，使 MFCC 对绝对音量不敏感
        if (!skipNormalization) {
            normalizeFrame(frame)
        }

        // 2. 应用汉宁窗
        applyHanningWindow(frame)
        
        val bufferSize = frame.size
        val amountOfCepstralCoefficients = 13 // We want 1..12
        val amountOfMelFilters = 128 // librosa default
        val lowerFilterFreq = 0f 
        val upperFilterFreq = sampleRate / 2f

        val mfcc = MFCC(bufferSize, sampleRate, amountOfCepstralCoefficients, amountOfMelFilters, lowerFilterFreq, upperFilterFreq)
        
        val bin = mfcc.magnitudeSpectrum(frame)
        val fbank = mfcc.melFilter(bin, mfcc.centerFrequencies)
        val f = mfcc.nonLinearTransformation(fbank)
        val mfccCoefficients = mfcc.cepCoefficients(f)
        
        return mfccCoefficients
    }

    /**
     * 对帧进行 RMS 归一化，解决音量差异问题
     */
    private fun normalizeFrame(frame: FloatArray) {
        var sumSq = 0f
        for (v in frame) sumSq += v * v
        val rms = sqrt(sumSq / frame.size)
        if (rms > 1e-6f) {
            for (i in frame.indices) {
                frame[i] /= rms
            }
        }
    }

    /**
     * Calculates signal energy (RMS-like)
     */
    fun calculateEnergy(signal: FloatArray): Float {
        var energy = 0f
        for (s in signal) energy += s * s
        return energy
    }

    private fun applyHanningWindow(signal: FloatArray) {
        for (i in signal.indices) {
            val window = 0.5f * (1.0f - Math.cos(2.0 * Math.PI * i / (signal.size - 1)).toFloat())
            signal[i] *= window
        }
    }

    /**
     * Calculates Euclidean distance between two feature vectors.
     */
    fun calculateEuclideanDistance(vec1: FloatArray, vec2: FloatArray): Float {
        if (vec1.size != vec2.size) return Float.MAX_VALUE
        var sum = 0.0
        for (i in vec1.indices) {
            val diff = (vec1[i] - vec2[i]).toDouble()
            sum += diff * diff
        }
        return sqrt(sum).toFloat()
    }

    /**
     * Calculates cosine similarity between two feature vectors.
     */
    fun calculateSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        if (vec1.size != vec2.size) return 0f

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            normA += vec1[i] * vec1[i]
            normB += vec2[i] * vec2[i]
        }

        if (normA == 0.0 || normB == 0.0) return 0f

        val similarity = (dotProduct / (sqrt(normA) * sqrt(normB))).toFloat()
        // Map Cosine similarity to 0-100 range
        return max(0f, similarity * 100)
    }

    /**
     * Calculates the RMS (Volume) of a signal.
     */
    fun calculateRMS(buffer: ShortArray): Float {
        var sum = 0.0
        for (sample in buffer) {
            sum += sample * (sample.toDouble())
        }
        return sqrt(sum / buffer.size).toFloat()
    }

    /**
     * Computes the average MFCC vector for a longer signal.
     */
    fun computeAverageMFCC(signal: FloatArray, frameSize: Int = 1024, sampleRate: Float = 44100f): FloatArray {
        if (signal.size < frameSize) return FloatArray(13)

        val numChunks = signal.size / frameSize
        val avgMFCC = FloatArray(13)

        for (i in 0 until numChunks) {
            val chunk = FloatArray(frameSize)
            System.arraycopy(signal, i * frameSize, chunk, 0, frameSize)
            val mfcc = calculateMFCC(chunk, sampleRate)
            for (j in mfcc.indices) {
                if (j < avgMFCC.size) {
                    avgMFCC[j] += mfcc[j]
                }
            }
        }

        for (j in avgMFCC.indices) {
            avgMFCC[j] /= numChunks
        }

        return avgMFCC
    }
}
