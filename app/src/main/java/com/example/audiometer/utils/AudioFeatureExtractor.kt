package com.example.audiometer.utils

import be.tarsos.dsp.mfcc.MFCC
import kotlin.math.max
import kotlin.math.sqrt

class AudioFeatureExtractor {

    /**
     * Calculates MFCC features for a given audio buffer.
     * Includes Hanning window and excludes C0 by default.
     */
    fun calculateMFCC(signal: FloatArray, sampleRate: Float = 16000f): FloatArray {
        val frame = signal.copyOf()
        applyHanningWindow(frame)
        
        val bufferSize = frame.size
        val amountOfCepstralCoefficients = 13 // We want 1..12
        val amountOfMelFilters = 128 // librosa default
        val lowerFilterFreq = 0f // librosa default is 0
        val upperFilterFreq = sampleRate / 2f

        val mfcc = MFCC(bufferSize, sampleRate, amountOfCepstralCoefficients, amountOfMelFilters, lowerFilterFreq, upperFilterFreq)
        
        val bin = mfcc.magnitudeSpectrum(frame)
        val fbank = mfcc.melFilter(bin, mfcc.centerFrequencies)
        val f = mfcc.nonLinearTransformation(fbank)
        val mfccCoefficients = mfcc.cepCoefficients(f)
        
        return mfccCoefficients
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
