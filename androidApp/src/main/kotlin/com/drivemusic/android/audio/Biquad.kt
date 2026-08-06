package com.drivemusic.android.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A single biquad section, using the standard RBJ cookbook coefficients.
 *
 * This exists because Android has no equivalent of the `AVAudioUnitEQ` node the iOS app filters
 * each slot with. `android.media.audiofx.Equalizer` attaches to an audio *session*, not to one
 * player, so it cannot express "filter the outgoing track while leaving the incoming one alone" —
 * which is the entire substance of a DJ transition. Owning the filter means owning the samples,
 * which is what `TransitionAudioProcessor` does.
 *
 * Deliberately plain Kotlin with no Android or Media3 types: the coefficient math is the part most
 * likely to be subtly wrong, and it is worth being able to test it directly.
 *
 * Coefficients are computed on whatever thread sets them and read on the audio thread. They are
 * swapped as one immutable [Coefficients] object precisely so a partially-updated set can never be
 * read — assigning five `Double` fields one by one is how a filter blows up mid-transition.
 */
class Biquad {
    data class Coefficients(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    ) {
        companion object {
            /** Passes everything through untouched. */
            val bypass = Coefficients(1.0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    @Volatile
    var coefficients: Coefficients = Coefficients.bypass

    // State is per-channel and lives only on the audio thread.
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    fun process(input: Double): Double {
        val c = coefficients
        val output = c.b0 * input + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
        x2 = x1; x1 = input
        y2 = y1; y1 = output
        return output
    }

    companion object {
        /** Butterworth-ish default. Higher would resonate at the cutoff, which reads as a whistle. */
        const val DEFAULT_Q = 0.707

        /**
         * At or below this, a high-pass is treated as open. Matches
         * `TransitionFilterRange.OPEN_LOW_FREQUENCY`, deliberately duplicated rather than imported
         * — this file is plain DSP with no dependency on the transition model, and the number is a
         * fact about hearing, not about transitions.
         */
        const val HIGH_PASS_BYPASS_HZ = 20.0

        fun lowPass(frequency: Double, sampleRate: Double, q: Double = DEFAULT_Q): Coefficients {
            // At or above Nyquist the filter does nothing, and the math degenerates.
            if (frequency >= sampleRate / 2) return Coefficients.bypass
            val w0 = 2 * PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            return Coefficients(
                b0 = ((1 - cosW0) / 2) / a0,
                b1 = (1 - cosW0) / a0,
                b2 = ((1 - cosW0) / 2) / a0,
                a1 = (-2 * cosW0) / a0,
                a2 = (1 - alpha) / a0,
            )
        }

        fun highPass(frequency: Double, sampleRate: Double, q: Double = DEFAULT_Q): Coefficients {
            // At or below the open end of the sweep there is nothing musical to remove, so this
            // bypasses rather than running a filter for an inaudible result. Not just an
            // optimisation: a 20Hz high-pass at 48kHz puts both poles within 0.004 of the unit
            // circle, which rings for a very long time after any discontinuity — and "open" is the
            // position the filter sits at whenever no transition is running, i.e. nearly always.
            if (frequency <= HIGH_PASS_BYPASS_HZ) return Coefficients.bypass
            val w0 = 2 * PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            return Coefficients(
                b0 = ((1 + cosW0) / 2) / a0,
                b1 = (-(1 + cosW0)) / a0,
                b2 = ((1 + cosW0) / 2) / a0,
                a1 = (-2 * cosW0) / a0,
                a2 = (1 - alpha) / a0,
            )
        }

        /**
         * Low shelf — the bass swap. A shelf rather than a high-pass sweep because the point is to
         * take a track's low end *down* by a known amount while leaving its character intact, not
         * to remove everything below a moving corner.
         */
        fun lowShelf(
            frequency: Double,
            gainDb: Double,
            sampleRate: Double,
            slope: Double = 1.0,
        ): Coefficients {
            if (gainDb == 0.0) return Coefficients.bypass
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
            val twoSqrtAAlpha = 2 * sqrt(a) * alpha
            val a0 = (a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha
            return Coefficients(
                b0 = (a * ((a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha)) / a0,
                b1 = (2 * a * ((a - 1) - (a + 1) * cosW0)) / a0,
                b2 = (a * ((a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha)) / a0,
                a1 = (-2 * ((a - 1) + (a + 1) * cosW0)) / a0,
                a2 = ((a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha) / a0,
            )
        }
    }
}
