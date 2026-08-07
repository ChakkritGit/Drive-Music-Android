package com.drivemusic.android.audio

import kotlin.math.roundToInt

/**
 * A Schroeder reverb — four parallel comb filters into two series allpass sections, per channel.
 *
 * Written by hand because Media3 has no reverb processor and `android.media.audiofx.PresetReverb`
 * attaches to an audio *session*, which would wash both slots at once. A transition needs the
 * outgoing track drowned while the incoming one stays dry, so the reverb has to live inside the
 * slot's own chain like everything else.
 *
 * The classic four-comb/two-allpass arrangement rather than a full Freeverb (eight combs, four
 * allpasses): this is a transition effect that runs for a few seconds under a track that is on its
 * way out, not a room simulation. Four combs is enough to avoid the metallic ring a smaller set
 * has, and half the per-sample work.
 *
 * Comb delays are mutually prime at 44.1kHz so their echo trains do not line up and reinforce into
 * a pitched resonance, and are scaled to whatever rate the stream actually runs at.
 */
class Reverb(sampleRate: Double) {

    /** Delay lengths in samples at 44.1kHz, from the standard Schroeder/Freeverb tuning. */
    private companion object {
        val COMB_DELAYS = intArrayOf(1_116, 1_188, 1_277, 1_356)
        val ALLPASS_DELAYS = intArrayOf(556, 441)
        const val REFERENCE_RATE = 44_100.0

        /** Comb feedback. Around 0.84 gives a tail of roughly a second and a half. */
        const val ROOM = 0.84f

        /** How much of each echo's top end is lost on every pass, so the tail darkens as it fades. */
        const val DAMPING = 0.2f

        const val ALLPASS_FEEDBACK = 0.5f
    }

    private class Comb(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        private var filterStore = 0f

        fun clear() {
            buffer.fill(0f)
            filterStore = 0f
            index = 0
        }

        fun process(input: Float): Float {
            val output = buffer[index]
            // One-pole lowpass inside the feedback path — this is what makes the tail decay
            // darker rather than just quieter, which is how a real room behaves.
            filterStore = output * (1 - DAMPING) + filterStore * DAMPING
            buffer[index] = input + filterStore * ROOM
            index = (index + 1) % buffer.size
            return output
        }
    }

    private class Allpass(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0

        fun clear() {
            buffer.fill(0f)
            index = 0
        }

        fun process(input: Float): Float {
            val buffered = buffer[index]
            val output = -input + buffered
            buffer[index] = input + buffered * ALLPASS_FEEDBACK
            index = (index + 1) % buffer.size
            return output
        }
    }

    private val scale = sampleRate / REFERENCE_RATE
    private val combs = COMB_DELAYS.map { Comb((it * scale).roundToInt().coerceAtLeast(1)) }
    private val allpasses = ALLPASS_DELAYS.map { Allpass((it * scale).roundToInt().coerceAtLeast(1)) }

    fun clear() {
        combs.forEach { it.clear() }
        allpasses.forEach { it.clear() }
    }

    /** The wet signal for one sample. Mixing it with the dry signal is the caller's job. */
    fun process(input: Float): Float {
        var wet = 0f
        // Parallel: each comb hears the same input and their outputs sum. Scaled by the count so
        // the wet level does not depend on how many combs there happen to be.
        for (comb in combs) wet += comb.process(input)
        wet /= combs.size

        // Series: the allpasses smear the comb echoes into something dense enough to read as a
        // space rather than as four distinct repeats.
        for (allpass in allpasses) wet = allpass.process(wet)
        return wet
    }
}
