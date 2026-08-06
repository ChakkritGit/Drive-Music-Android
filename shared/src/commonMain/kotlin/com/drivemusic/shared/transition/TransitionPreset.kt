package com.drivemusic.shared.transition

import com.drivemusic.shared.transition.TransitionCurve.Keyframe as K
import kotlinx.serialization.Serializable

/**
 * The presets offered in the editor, each just a named [TransitionShape]. A preset is a starting
 * point, not a mode: selecting one fills the lanes in, and adjusting any lane afterwards leaves a
 * shape that no longer matches any preset, which is exactly what gets stored.
 */
@Serializable
enum class TransitionPreset {
    /** A plain volume crossfade, nothing else. */
    FADE,

    /**
     * The DJ bass swap: the incoming track arrives with its low end pulled back so it does not
     * collide with the outgoing one's, and opens up as it takes over.
     */
    MIX,

    /**
     * The outgoing track washes out — filtered down and drowned in reverb — while the incoming one
     * comes up underneath. Reads as a lift rather than a handover.
     */
    RISE,

    /**
     * Both tracks sit at full level through the middle, with only their bass swapped. The most
     * seamless of the four when the two tracks are compatible, and the muddiest when they are not.
     */
    BLEND;

    val shape: TransitionShape
        get() = when (this) {
            FADE -> TransitionShape(
                outgoingVolume = TransitionCurve.ramp(1.0, 0.0),
                incomingVolume = TransitionCurve.ramp(0.0, 1.0),
            )

            MIX -> TransitionShape(
                // Held fuller for longer on both sides than an equal-power crossfade would be. The
                // two tracks overlap at close to full level through the middle third, and what
                // keeps that from turning to mud is the bass swap and the filters below — not
                // turning either track down. Volume alone is what a crossfade has, and leaning on
                // it is exactly what made this sound like one.
                outgoingVolume = TransitionCurve(
                    listOf(K(0.0, 1.0), K(0.55, 0.95), K(0.8, 0.6), K(1.0, 0.0))
                ),
                incomingVolume = TransitionCurve(
                    listOf(K(0.0, 0.0), K(0.2, 0.6), K(0.45, 0.95), K(1.0, 1.0))
                ),
                // The outgoing track loses its top end late and completely — it thins out and
                // recedes rather than simply getting quieter, which gives the exit its distance.
                outgoingLowPass = TransitionCurve(
                    listOf(K(0.0, 0.0), K(0.45, 0.15), K(0.75, 0.6), K(1.0, 1.0))
                ),
                // The incoming track arrives filtered and opens up early — by the midpoint it is
                // full-range and carrying the mix while the outgoing one is still audible behind
                // it. That overlap of a *complete* track over a receding one is the depth.
                incomingHighPass = TransitionCurve(
                    listOf(K(0.0, 1.0), K(0.25, 0.6), K(0.5, 0.0), K(1.0, 0.0))
                ),
                // Only one track can own the low end; two sharing it is the muddiness people hear
                // as "just a crossfade with EQ". Swapped over a tenth of the transition rather
                // than instantly, so it reads as a handover rather than a cut.
                outgoingBass = TransitionCurve(
                    listOf(K(0.0, 0.0), K(0.4, 0.0), K(0.5, -18.0), K(1.0, -24.0))
                ),
                incomingBass = TransitionCurve(
                    listOf(K(0.0, -24.0), K(0.4, -18.0), K(0.5, 0.0), K(1.0, 0.0))
                ),
                // A little space under the outgoing track as it leaves. Kept low and starting
                // late: enough that the tail sounds like it is moving away rather than being faded
                // down, not so much that it turns into the Rise wash.
                outgoingReverb = TransitionCurve(
                    listOf(K(0.0, 0.0), K(0.6, 0.0), K(1.0, 35.0))
                ),
            )

            RISE -> TransitionShape(
                outgoingVolume = TransitionShape.equalPowerDown,
                incomingVolume = TransitionShape.equalPowerUp,
                // Closes much further than MIX — the outgoing track is meant to disappear into a
                // wash rather than hand over cleanly.
                outgoingLowPass = TransitionCurve.ramp(0.0, 1.0),
                incomingHighPass = TransitionCurve(
                    listOf(K(0.0, 0.7), K(0.6, 0.0), K(1.0, 0.0))
                ),
                outgoingReverb = TransitionCurve.ramp(0.0, 80.0),
                looping = TransitionLooping.OUTGOING_ONE_BAR,
            )

            BLEND -> TransitionShape(
                // Both held near full through the middle — the volume lanes barely move, and the
                // whole transition is carried by the bass swap below.
                outgoingVolume = TransitionCurve(
                    listOf(K(0.0, 1.0), K(0.75, 1.0), K(1.0, 0.0))
                ),
                incomingVolume = TransitionCurve(
                    listOf(K(0.0, 0.0), K(0.25, 1.0), K(1.0, 1.0))
                ),
                // A hard swap at the midpoint: whichever track owns the low end owns the groove.
                outgoingBass = TransitionCurve(
                    listOf(K(0.0, 0.0), K(0.45, 0.0), K(0.55, -24.0), K(1.0, -24.0))
                ),
                incomingBass = TransitionCurve(
                    listOf(K(0.0, -24.0), K(0.45, -24.0), K(0.55, 0.0), K(1.0, 0.0))
                ),
            )
        }

    companion object {
        /** Which preset a shape came from, or null once it has been edited away from all of them. */
        fun matching(shape: TransitionShape): TransitionPreset? =
            entries.firstOrNull { it.shape == shape }
    }
}
