package com.drivemusic.android.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The filter coefficients are the part of the Android audio path most likely to be quietly wrong —
 * a mistake here is not a crash, it is a transition that sounds thin or hollow, which is exactly
 * the kind of thing that gets rationalised away as "mixing is hard". So these measure what the
 * filters actually do to a signal rather than checking the arithmetic against itself.
 */
class BiquadTest {
    private val sampleRate = 48_000.0

    /** Runs a sine through [biquad] and returns output RMS relative to input RMS, in dB. */
    private fun gainDbAt(frequency: Double, coefficients: Biquad.Coefficients): Double {
        val biquad = Biquad().apply { this.coefficients = coefficients }
        val total = 48_000            // one second
        val settle = 4_800            // discard the first tenth while the filter settles
        var inputSum = 0.0
        var outputSum = 0.0

        for (n in 0 until total) {
            val input = sin(2 * PI * frequency * n / sampleRate)
            val output = biquad.process(input)
            if (n >= settle) {
                inputSum += input * input
                outputSum += output * output
            }
        }
        val inputRms = sqrt(inputSum / (total - settle))
        val outputRms = sqrt(outputSum / (total - settle))
        return 20 * log10(outputRms / inputRms)
    }

    @Test
    fun lowPassPassesLowsAndStopsHighs() {
        val lp = Biquad.lowPass(1_000.0, sampleRate)
        assertTrue(abs(gainDbAt(100.0, lp)) < 1.0, "100Hz should pass a 1kHz low-pass untouched")
        assertTrue(gainDbAt(10_000.0, lp) < -30.0, "10kHz should be well down through a 1kHz low-pass")
    }

    @Test
    fun highPassPassesHighsAndStopsLows() {
        val hp = Biquad.highPass(1_000.0, sampleRate)
        assertTrue(abs(gainDbAt(10_000.0, hp)) < 1.0, "10kHz should pass a 1kHz high-pass untouched")
        assertTrue(gainDbAt(100.0, hp) < -30.0, "100Hz should be well down through a 1kHz high-pass")
    }

    /** -3dB at the corner is the definition of the cutoff, and the check that Q is right. */
    @Test
    fun theCornerFrequencyIsMinusThreeDb() {
        assertEquals(-3.0, gainDbAt(1_000.0, Biquad.lowPass(1_000.0, sampleRate)), 0.5)
        assertEquals(-3.0, gainDbAt(1_000.0, Biquad.highPass(1_000.0, sampleRate)), 0.5)
    }

    /**
     * The bass swap. A -24dB shelf has to actually take 24dB off the low end while leaving the
     * midrange where it was — that separation is what makes a swap sound like a handover rather
     * than one track ducking.
     */
    @Test
    fun lowShelfCutsTheBassAndLeavesTheMidrangeAlone() {
        val shelf = Biquad.lowShelf(TransitionAudioProcessor.BASS_SHELF_HZ, -24.0, sampleRate)
        assertEquals(-24.0, gainDbAt(50.0, shelf), 1.5)
        assertTrue(abs(gainDbAt(4_000.0, shelf)) < 1.0, "4kHz should be untouched by a bass shelf")
    }

    @Test
    fun lowShelfAtZeroDbIsBypass() {
        assertEquals(Biquad.Coefficients.bypass, Biquad.lowShelf(250.0, 0.0, sampleRate))
    }

    /**
     * The open ends of both sweeps must be genuinely inaudible, or every transition would start
     * and end with a click as a filter engages.
     */
    @Test
    fun theOpenEndsOfBothSweepsAreTransparent() {
        val openLowPass = Biquad.lowPass(20_000.0, sampleRate)
        assertTrue(abs(gainDbAt(1_000.0, openLowPass)) < 0.5)

        // The open end of the high-pass sweep is bypassed outright rather than run as a filter —
        // see `HIGH_PASS_BYPASS_HZ`.
        val openHighPass = Biquad.highPass(Biquad.HIGH_PASS_BYPASS_HZ, sampleRate)
        assertEquals(Biquad.Coefficients.bypass, openHighPass)
        assertEquals(0.0, gainDbAt(1_000.0, openHighPass), 0.001)

        // Just above it the filter is real, and still has to be transparent in the musical range.
        assertTrue(abs(gainDbAt(1_000.0, Biquad.highPass(25.0, sampleRate))) < 0.5)
    }

    /** Degenerate inputs must not produce NaN coefficients, which would silence the slot forever. */
    @Test
    fun degenerateFrequenciesAreHandled() {
        assertEquals(Biquad.Coefficients.bypass, Biquad.lowPass(sampleRate, sampleRate))
        assertEquals(Biquad.Coefficients.bypass, Biquad.lowPass(sampleRate / 2, sampleRate))

        val coefficients = Biquad.lowShelf(250.0, -24.0, sampleRate)
        listOf(coefficients.b0, coefficients.b1, coefficients.b2, coefficients.a1, coefficients.a2)
            .forEach { assertTrue(it.isFinite(), "coefficient was $it") }
    }

    @Test
    fun resetClearsState() {
        val biquad = Biquad().apply { coefficients = Biquad.lowPass(1_000.0, sampleRate) }
        repeat(100) { biquad.process(1.0) }
        biquad.reset()
        // With cleared history the first sample of a new signal is only the direct path.
        assertEquals(biquad.coefficients.b0, biquad.process(1.0), 1e-12)
    }
}
