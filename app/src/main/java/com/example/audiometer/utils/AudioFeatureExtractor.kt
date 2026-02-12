package com.example.audiometer.utils

import be.tarsos.dsp.mfcc.MFCC
import kotlin.math.max
import kotlin.math.sqrt

class AudioFeatureExtractor {

    /**
     * Calculates MFCC features for a given audio buffer.
     * 适配 TarsosDSP 2.5 API：手动调用各个处理步骤
     */
    fun calculateMFCC(signal: FloatArray, sampleRate: Float = 44100f): FloatArray {
        val bufferSize = signal.size
        val amountOfCepstralCoefficients = 13
        val amountOfMelFilters = 40
        val lowerFilterFreq = 133.33f
        val upperFilterFreq = sampleRate / 2f

        val mfcc = MFCC(bufferSize, sampleRate, amountOfCepstralCoefficients, amountOfMelFilters, lowerFilterFreq, upperFilterFreq)
        
        // 手动调用 MFCC 的各个处理步骤
        val bin = mfcc.magnitudeSpectrum(signal.copyOf())
        val fbank = mfcc.melFilter(bin, mfcc.centerFrequencies)
        val f = mfcc.nonLinearTransformation(fbank)
        val mfccCoefficients = mfcc.cepCoefficients(f)
        
        return mfccCoefficients
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
