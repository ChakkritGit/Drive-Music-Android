package com.drivemusic.shared.transition

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The two ends of a filter sweep. "Open" means inaudible: a high-pass at 20Hz and a low-pass at
 * 20kHz both pass the whole musical range, so a sweep can start or end there without a click.
 * "Closed" is how far a transition pushes them — 400Hz strips a track's bass without hollowing it
 * out entirely, which is the point of a bass swap.
 */
object TransitionFilterRange {
    const val OPEN_LOW_FREQUENCY = 20f
    const val OPEN_HIGH_FREQUENCY = 20_000f
    const val CLOSED_FREQUENCY = 400f

    /**
     * Maps a lane's normalized 0..1 position to a frequency, geometrically. Pitch — and how a
     * filter sweep reads to the ear — is logarithmic in frequency, so a lane moving linearly in Hz
     * would spend nearly all its travel in a range that is already inaudible and lurch through the
     * part that matters. 0 is fully open, 1 fully closed, for both filter types.
     */
    fun highPassFrequency(position: Double): Float {
        val clamped = min(1.0, max(0.0, position))
        val open = OPEN_LOW_FREQUENCY.toDouble()
        val closed = CLOSED_FREQUENCY.toDouble()
        return (open * (closed / open).pow(clamped)).toFloat()
    }

    fun lowPassFrequency(position: Double): Float {
        val clamped = min(1.0, max(0.0, position))
        val open = OPEN_HIGH_FREQUENCY.toDouble()
        val closed = CLOSED_FREQUENCY.toDouble()
        return (open * (closed / open).pow(clamped)).toFloat()
    }
}

/**
 * What loops during a transition, if anything. Looping the tail of the outgoing track is how a DJ
 * stretches a phrase to buy time for the next one to arrive on a downbeat.
 */
@Serializable
enum class TransitionLooping {
    NONE,
    OUTGOING_ONE_BAR,
    OUTGOING_TWO_BARS;

    /** How many bars are looped, or null for [NONE]. Requires a beat grid to mean anything. */
    val bars: Int?
        get() = when (this) {
            NONE -> null
            OUTGOING_ONE_BAR -> 1
            OUTGOING_TWO_BARS -> 2
        }
}

/**
 * Every automation lane of one transition. The lanes are *data*, so the editor can offer per-
 * transition EQ, filtering, effects and looping without each needing new code in the ramp loop.
 *
 * All lanes run over the same normalized 0..1 span, and a lane holding one value costs nothing
 * (see [TransitionCurve.isConstant]) — a plain fade is this same structure with every lane but the
 * two volumes held flat.
 */
@Serializable
data class TransitionShape(
    /** Gain multipliers, 0..1, applied on top of each slot's own level. */
    val outgoingVolume: TransitionCurve,
    val incomingVolume: TransitionCurve,
    /** Normalized filter positions, 0 = open, 1 = closed — see [TransitionFilterRange]. */
    val outgoingLowPass: TransitionCurve = TransitionCurve.constant(0.0),
    val incomingHighPass: TransitionCurve = TransitionCurve.constant(0.0),
    /**
     * Per-slot bass shelf in dB — the "bass swap" a DJ mix is built on, done as an EQ cut rather
     * than a filter sweep so the rest of each track's low end stays where it was.
     */
    val outgoingBass: TransitionCurve = TransitionCurve.constant(0.0),
    val incomingBass: TransitionCurve = TransitionCurve.constant(0.0),
    /** Reverb wet/dry, 0..100. Used by "Rise", where the outgoing track washes out. */
    val outgoingReverb: TransitionCurve = TransitionCurve.constant(0.0),
    val looping: TransitionLooping = TransitionLooping.NONE,
) {
    companion object {
        // Two linear ramps crossing at the midpoint sum to noticeably *less* than either alone
        // there — uncorrelated signals add by power, not amplitude — which is the classic mid-mix
        // volume sag. These sample a quarter-turn of cos/sin at five points instead, which holds
        // the summed power near constant. Five is where adding more stopped mattering.
        val equalPowerDown = TransitionCurve(
            (0..4).map { step ->
                val t = step / 4.0
                TransitionCurve.Keyframe(t, cos(t * PI / 2))
            }
        )

        val equalPowerUp = TransitionCurve(
            (0..4).map { step ->
                val t = step / 4.0
                TransitionCurve.Keyframe(t, sin(t * PI / 2))
            }
        )
    }
}
