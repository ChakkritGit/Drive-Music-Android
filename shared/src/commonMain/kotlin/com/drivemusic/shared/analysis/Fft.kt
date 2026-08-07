package com.drivemusic.shared.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An in-place radix-2 FFT, and the magnitude spectrum of a real signal built on it.
 *
 * Pure Kotlin in `commonMain` rather than an NDK library. The transform runs on 2048 points a few
 * thousand times per track, off the main thread, once per track for the life of a download — a
 * hand-written Kotlin FFT is comfortably fast enough for that, and it keeps the detectors that use
 * it in the same shared module as the mix points they feed, where iOS and Android are provably
 * computing the same thing rather than two implementations that agree today.
 */
object Fft {
    /**
     * Magnitudes of the first `size / 2` bins of [samples], which must be a power of two long.
     *
     * Reuses the caller's scratch arrays. Allocating two arrays per frame is what makes a
     * per-frame transform expensive in a garbage-collected language — the arithmetic is not.
     */
    fun magnitudes(
        samples: FloatArray,
        real: FloatArray,
        imaginary: FloatArray,
        output: FloatArray,
    ) {
        val size = samples.size
        samples.copyInto(real)
        imaginary.fill(0f)
        transform(real, imaginary)
        for (bin in output.indices) {
            output[bin] = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
        }
        // The DC and Nyquist bins carry no pitch and, at DC, a large offset that would swamp
        // everything else in a flux sum.
        if (output.isNotEmpty()) output[0] = 0f
        if (size == 0) return
    }

    /** Cooley–Tukey, decimation in time, in place. */
    fun transform(real: FloatArray, imaginary: FloatArray) {
        val n = real.size
        if (n <= 1) return

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tempReal = real[i]; real[i] = real[j]; real[j] = tempReal
                val tempImaginary = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = tempImaginary
            }
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wReal = cos(angle).toFloat()
            val wImaginary = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var currentReal = 1f
                var currentImaginary = 0f
                for (offset in 0 until length / 2) {
                    val a = start + offset
                    val b = a + length / 2
                    val productReal = currentReal * real[b] - currentImaginary * imaginary[b]
                    val productImaginary = currentReal * imaginary[b] + currentImaginary * real[b]
                    real[b] = real[a] - productReal
                    imaginary[b] = imaginary[a] - productImaginary
                    real[a] += productReal
                    imaginary[a] += productImaginary
                    val nextReal = currentReal * wReal - currentImaginary * wImaginary
                    currentImaginary = currentReal * wImaginary + currentImaginary * wReal
                    currentReal = nextReal
                }
                start += length
            }
            length = length shl 1
        }
    }

    /** A Hann window of [size] points, normalised the way `vDSP_hann_window` normalises it. */
    fun hannWindow(size: Int): FloatArray = FloatArray(size) { index ->
        (0.5 * (1 - cos(2 * PI * index / (size - 1)))).toFloat()
    }
}
