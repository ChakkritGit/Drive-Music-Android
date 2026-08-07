package com.drivemusic.android.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A reverb is a feedback network, and the failure mode of a feedback network designed wrong is not
 * a wrong sound — it is a tail that never decays, or one that grows until it clips. Both are
 * unrecoverable once running, so the decay is asserted rather than assumed.
 */
class ReverbTest {
    private val sampleRate = 44_100.0

    /** Feeds one impulse and returns the wet output for [seconds] afterwards. */
    private fun impulseResponse(seconds: Double): FloatArray {
        val reverb = Reverb(sampleRate)
        val samples = (sampleRate * seconds).toInt()
        return FloatArray(samples) { index ->
            reverb.process(if (index == 0) 1f else 0f)
        }
    }

    private fun peak(values: FloatArray, from: Int, to: Int): Float {
        var maximum = 0f
        for (i in from until minOf(to, values.size)) maximum = maxOf(maximum, abs(values[i]))
        return maximum
    }

    /** Something has to come out, or the effect is an expensive no-op. */
    @Test
    fun anImpulseProducesATail() {
        val response = impulseResponse(1.0)
        assertTrue(peak(response, 0, response.size) > 0.01f, "the reverb produced no output")
    }

    /**
     * The decay. Feedback below 1 has to mean the tail dies out — this is the assertion that a
     * mistuned comb feedback or a sign error would fail, and the one that matters most.
     */
    @Test
    fun theTailDecays() {
        val response = impulseResponse(4.0)
        val early = peak(response, 0, (sampleRate * 0.2).toInt())
        val late = peak(response, (sampleRate * 3.0).toInt(), response.size)

        assertTrue(early > 0.01f, "no early reflections")
        assertTrue(late < early * 0.2f, "after 3s the tail was still $late against an early $early")
    }

    /** A tail that outlasts a transition would still be ringing over the next track. */
    @Test
    fun theTailIsShorterThanATypicalTransition() {
        val response = impulseResponse(8.0)
        val early = peak(response, 0, (sampleRate * 0.2).toInt())
        val afterSixSeconds = peak(response, (sampleRate * 6.0).toInt(), response.size)

        assertTrue(
            afterSixSeconds < early * 0.02f,
            "still audible after 6s: $afterSixSeconds against $early"
        )
    }

    /** Nothing may grow without bound, whatever it is fed. */
    @Test
    fun sustainedFullScaleInputStaysBounded() {
        val reverb = Reverb(sampleRate)
        var maximum = 0f
        for (index in 0 until (sampleRate * 5).toInt()) {
            // Worst case for a feedback network: a constant, not a decaying signal.
            val output = reverb.process(1f)
            maximum = maxOf(maximum, abs(output))
            assertTrue(output.isFinite(), "output became $output at sample $index")
        }
        assertTrue(maximum < 20f, "wet output reached $maximum — the network is not stable")
    }

    @Test
    fun clearSilencesTheTail() {
        val reverb = Reverb(sampleRate)
        repeat(4_410) { reverb.process(1f) }
        reverb.clear()

        var maximum = 0f
        repeat(1_000) { maximum = maxOf(maximum, abs(reverb.process(0f))) }
        assertTrue(maximum == 0f, "a cleared reverb still output $maximum")
    }

    /** The delays must scale with the stream, or the room changes size with the sample rate. */
    @Test
    fun theRoomIsTheSameSizeAtAnySampleRate() {
        fun tailLengthSeconds(rate: Double): Double {
            val reverb = Reverb(rate)
            val total = (rate * 4).toInt()
            var last = 0
            for (index in 0 until total) {
                val value = reverb.process(if (index == 0) 1f else 0f)
                if (abs(value) > 0.001f) last = index
            }
            return last / rate
        }

        val at44k = tailLengthSeconds(44_100.0)
        val at48k = tailLengthSeconds(48_000.0)
        assertTrue(abs(at44k - at48k) < 0.25, "tail was ${at44k}s at 44.1k but ${at48k}s at 48k")
    }
}
