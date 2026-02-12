package com.example.audiometer.utils

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AudioFeatureExtractor {

    // Simple FFT implementation
    fun calculateFFT(signal: FloatArray): FloatArray {
        val n = signal.size
        // Must be power of 2 for this simple implementation or handle otherwise.
        // For simplicity, we assume we feed power of 2 or pad it.
        // But for robust implementation, let's just use Magnitude Spectrum

        // This is a placeholder for a complex FFT algo.
        // We will return a dummy spectrum if complexity is high,
        // but let's try a simple DFT or Spectrum magnitude for limited bands.

        // Let's implement valid but slow DFT if N is small, or just average energy for bands.
        // For efficiency, let's use a simplified spectral density approach.

        // Actually, to keep it fast and simple for this agent task,
        // let's compute RMS (Volume) and Zero Crossing Rate (ZCR) as basic features.
        // If we want "Similarity", we really need FFT.

        // Let's implement a very basic Real Fourier Transform for magnitude.

        val spectrum = FloatArray(n / 2)
        for (k in 0 until n / 2) {
            var real = 0.0
            var imag = 0.0
            for (t in 0 until n) {
                val angle = 2 * Math.PI * t * k / n
                real += signal[t] * cos(angle)
                imag -= signal[t] * sin(angle)
            }
            spectrum[k] = sqrt(real.pow(2) + imag.pow(2)).toFloat()
        }
        return spectrum
    }

    // Similarity between two spectrums (Correlation)
    fun calculateSimilarity(spec1: FloatArray, spec2: FloatArray): Float {
        if (spec1.size != spec2.size) return 0f

        // Cosine Similarity
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in spec1.indices) {
            dotProduct += spec1[i] * spec2[i]
            normA += spec1[i] * spec1[i]
            normB += spec2[i] * spec2[i]
        }

        if (normA == 0.0 || normB == 0.0) return 0f

        val similarity = (dotProduct / (sqrt(normA) * sqrt(normB))).toFloat()
        // Map -1..1 to 0..100
        return max(0f, similarity * 100)
    }

    fun calculateRMS(buffer: ShortArray): Float {
        var sum = 0.0
        for (sample in buffer) {
            sum += sample * sample
        }
        return sqrt(sum / buffer.size).toFloat()
    }

    fun computeAverageSpectrum(signal: FloatArray, fftSize: Int = 1024): FloatArray {
        if (signal.size < fftSize) return FloatArray(fftSize / 2)

        val numChunks = signal.size / fftSize
        val avgSpectrum = FloatArray(fftSize / 2)

        for (i in 0 until numChunks) {
            val chunk = FloatArray(fftSize)
            System.arraycopy(signal, i * fftSize, chunk, 0, fftSize)
            val spectrum = calculateFFT(chunk)
            for (j in spectrum.indices) {
                avgSpectrum[j] += spectrum[j]
            }
        }

        for (j in avgSpectrum.indices) {
            avgSpectrum[j] /= numChunks
        }

        return avgSpectrum
    }
}


