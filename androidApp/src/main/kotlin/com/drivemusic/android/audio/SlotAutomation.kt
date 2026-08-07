package com.drivemusic.android.audio

import com.drivemusic.shared.transition.TransitionFilterRange
import com.drivemusic.shared.transition.TransitionShape

/**
 * Everything one slot's signal chain needs at one instant of a transition.
 *
 * A value rather than a set of calls, for the same reason `TransitionPlan` is a value on the iOS
 * side: it makes what the transition *does* inspectable and testable without an audio engine
 * anywhere near it. The engine's job is then only to apply it.
 */
data class SlotParameters(
    /** Gain multiplier, 0..1. */
    val volume: Double,
    /** Low-pass corner in Hz. [TransitionFilterRange.OPEN_HIGH_FREQUENCY] means open. */
    val lowPassHz: Double,
    /** High-pass corner in Hz. [TransitionFilterRange.OPEN_LOW_FREQUENCY] means open. */
    val highPassHz: Double,
    /** Bass shelf in dB, 0 for flat. */
    val bassDb: Double,
    /** Reverb wet mix, 0..100. 0 bypasses the reverb entirely. */
    val reverbWet: Double = 0.0,
) {
    companion object {
        /** Full level, every filter open — what a slot sits at outside a transition. */
        val open = SlotParameters(
            volume = 1.0,
            lowPassHz = TransitionFilterRange.OPEN_HIGH_FREQUENCY.toDouble(),
            highPassHz = TransitionFilterRange.OPEN_LOW_FREQUENCY.toDouble(),
            bassDb = 0.0,
            reverbWet = 0.0,
        )

        val silent = open.copy(volume = 0.0)
    }
}

/**
 * Reads a [TransitionShape]'s lanes at a point in the transition and turns them into the two
 * slots' parameters.
 *
 * This is the piece that makes the Android engine share its behavior with iOS rather than merely
 * resemble it: the curves come from `:shared`, so "what a Mix sounds like" is defined once. Only
 * the application of the numbers differs.
 *
 * Every lane of the shape is honored.
 */
object SlotAutomation {
    /**
     * @param t position within the transition, 0..1. Clamped, so a caller that overshoots by a
     *   tick — which every timer-driven ramp does — holds the end of the transition rather than
     *   extrapolating past it.
     */
    fun outgoing(shape: TransitionShape, t: Double): SlotParameters {
        val clamped = t.coerceIn(0.0, 1.0)
        return SlotParameters(
            volume = shape.outgoingVolume.valueAt(clamped),
            lowPassHz = TransitionFilterRange
                .lowPassFrequency(shape.outgoingLowPass.valueAt(clamped)).toDouble(),
            highPassHz = TransitionFilterRange.OPEN_LOW_FREQUENCY.toDouble(),
            bassDb = shape.outgoingBass.valueAt(clamped),
            reverbWet = shape.outgoingReverb.valueAt(clamped),
        )
    }

    fun incoming(shape: TransitionShape, t: Double): SlotParameters {
        val clamped = t.coerceIn(0.0, 1.0)
        return SlotParameters(
            volume = shape.incomingVolume.valueAt(clamped),
            lowPassHz = TransitionFilterRange.OPEN_HIGH_FREQUENCY.toDouble(),
            highPassHz = TransitionFilterRange
                .highPassFrequency(shape.incomingHighPass.valueAt(clamped)).toDouble(),
            bassDb = shape.incomingBass.valueAt(clamped),
            // The shape has no incoming reverb lane — a track arriving drenched would sound like
            // a mistake rather than an effect, and no preset asks for it.
            reverbWet = 0.0,
        )
    }

    /**
     * The outgoing reverb lane, 0..100. Applied by [TransitionAudioProcessor] via [Reverb].
     *
     * Media3 has no reverb processor and `android.media.audiofx.PresetReverb` attaches to an audio
     * session — it would wash *both* slots and defeat the point — so the reverb is hand-written
     * and lives inside the slot's own chain like every other lane.
     */
    fun reverbWet(shape: TransitionShape, t: Double): Double =
        shape.outgoingReverb.valueAt(t.coerceIn(0.0, 1.0))
}
