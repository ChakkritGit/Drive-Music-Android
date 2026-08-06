package com.drivemusic.shared.transition

import com.drivemusic.shared.model.TrackAnalysis
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.min

/**
 * A user's stored intent for one transition. Null fields mean "let the app decide", which is what
 * [AUTO] is: not a separate mode, just an override that has not been made.
 */
@Serializable
data class TransitionSettings(
    /**
     * The full automation shape, once the user has touched anything. Stored as the shape itself
     * rather than a preset name so an edited transition survives — a preset is only ever a
     * starting point, and [TransitionPreset.matching] recovers the name when it still fits.
     */
    val shape: TransitionShape? = null,
    val bars: Int? = null,
    val beatmatchEnabled: Boolean? = null,
    /**
     * Where in the outgoing track the mix begins, in seconds. Null lets the app choose. Used
     * exactly as given: a hand-placed start is a decision, not a suggestion to be snapped.
     */
    val outgoingStartSeconds: Double? = null,
    /** Where the incoming track starts playing from. Null uses its detected mix-in point. */
    val incomingStartSeconds: Double? = null,
) {
    val isAuto: Boolean
        get() = shape == null && bars == null && beatmatchEnabled == null &&
            outgoingStartSeconds == null && incomingStartSeconds == null

    companion object {
        val AUTO = TransitionSettings()

        /**
         * Transition lengths offered in the editor, in bars. Powers of two because that is how
         * popular music is phrased — a transition that is not a whole number of bars lands the
         * incoming downbeat somewhere the listener is not expecting one.
         */
        val barOptions = listOf(1, 2, 4, 8, 16)
    }
}

/**
 * Everything needed to actually run one transition, resolved from the two tracks' analyses and
 * whatever the user overrode. Computing this as a value up front — rather than having the ramp
 * loop reach for tempo and key as it goes — is what makes the decisions inspectable: the editor
 * can show exactly the plan that will run, and it can be reasoned about without an audio engine.
 */
data class TransitionPlan(
    val shape: TransitionShape,
    val duration: Double,
    /**
     * Where in the outgoing track the transition should start, in seconds. Null means "wherever
     * the caller's normal trigger fires".
     */
    val startSeconds: Double?,
    /** Where the incoming track should start from, so the two grids line up. */
    val incomingStartSeconds: Double,
    /**
     * Rate to play the incoming track at during the transition so its tempo matches the outgoing
     * one. 1 when beatmatching is off or either tempo is unknown.
     */
    val incomingRate: Float,
    /** The stretch of the outgoing track to loop, in seconds, or null for no loop. */
    val outgoingLoop: ClosedFloatingPointRange<Double>?,
) {
    companion object {
        /**
         * The most either track may be stretched. Beyond ~6% the time-stretch artifacts are
         * audible on percussive material, and tracks further apart than that do not belong
         * beat-matched anyway — the honest answer there is to play them back to back.
         */
        const val MAXIMUM_TEMPO_STRETCH = 0.06

        /**
         * Resolves a plan. [outgoing]/[incoming] may be null (never analyzed, or analysis found
         * nothing usable) — every step degrades to the un-analyzed behavior rather than refusing,
         * so an unanalyzed library still crossfades exactly as it did before.
         */
        fun resolve(
            settings: TransitionSettings,
            outgoing: TrackAnalysis?,
            incoming: TrackAnalysis?,
            /** Total length of the outgoing track, used only to cap the transition. */
            outgoingDuration: Double?,
            fallbackDuration: Double,
            autoMixEnabled: Boolean,
            beatmatchEnabledByDefault: Boolean,
        ): TransitionPlan {
            val shape = settings.shape
                ?: (if (autoMixEnabled) TransitionPreset.MIX else TransitionPreset.FADE).shape

            // Length: a bar count only means something when there is a tempo to measure bars
            // against. Without one, the user's global crossfade length is the only answer.
            val bars = settings.bars ?: defaultBars(shape)
            var duration = outgoing?.secondsForBars(bars) ?: fallbackDuration
            // Capped against the outgoing track itself. A bar count is a musical length, not a
            // fraction of a song: 16 bars at 70 BPM is nearly a minute, which on a short interlude
            // meant the transition was longer than the track it was leaving.
            if (outgoingDuration != null && outgoingDuration > 0) {
                duration = min(duration, outgoingDuration / 3)
            }

            // Only ever the user's own choice. When they have not made one this stays null and the
            // caller picks the start — see the mix-out point.
            val startSeconds = settings.outgoingStartSeconds

            // Skip whatever leads in before the incoming track arrives. Tracks routinely open with
            // silence or a lead-in, and starting from frame 0 pushes the whole grid out by that.
            val incomingStart = settings.incomingStartSeconds
                ?: incoming?.mixInSeconds
                ?: incoming?.firstBeatSeconds
                ?: 0.0

            val beatmatch = settings.beatmatchEnabled ?: beatmatchEnabledByDefault
            val rate = if (beatmatch) matchRate(outgoing, incoming) else 1f

            // A loop runs for the length of the transition, ending where the transition starts —
            // it is the tail of the outgoing track being held, not extra material added after it.
            var loop: ClosedFloatingPointRange<Double>? = null
            val loopBars = shape.looping.bars
            val loopLength = loopBars?.let { outgoing?.secondsForBars(it) }
            if (loopLength != null && startSeconds != null && startSeconds - loopLength >= 0) {
                loop = (startSeconds - loopLength)..startSeconds
            }

            return TransitionPlan(
                shape = shape,
                duration = duration,
                startSeconds = startSeconds,
                incomingStartSeconds = incomingStart,
                incomingRate = rate,
                outgoingLoop = loop,
            )
        }

        /**
         * A plain volume fade is a short, utilitarian thing; anything that filters, swaps bass or
         * washes out needs room to be heard doing it — 4 bars is roughly 8 seconds at 120 BPM,
         * which is what every DJ tool defaults to.
         */
        private fun defaultBars(shape: TransitionShape): Int {
            val isPlainFade = shape.outgoingLowPass.isConstant &&
                shape.incomingHighPass.isConstant &&
                shape.outgoingBass.isConstant &&
                shape.incomingBass.isConstant &&
                shape.outgoingReverb.isConstant
            return if (isPlainFade) 2 else 4
        }

        /**
         * The rate that makes the incoming tempo equal the outgoing one — or 1 when either tempo
         * is unknown or they are too far apart to match without audible damage.
         */
        private fun matchRate(outgoing: TrackAnalysis?, incoming: TrackAnalysis?): Float {
            val outgoingBpm = outgoing?.bpm ?: return 1f
            val incomingBpm = incoming?.bpm ?: return 1f
            if (outgoingBpm <= 0 || incomingBpm <= 0) return 1f
            val ratio = outgoingBpm / incomingBpm
            if (abs(ratio - 1) > MAXIMUM_TEMPO_STRETCH) return 1f
            return ratio.toFloat()
        }
    }
}
