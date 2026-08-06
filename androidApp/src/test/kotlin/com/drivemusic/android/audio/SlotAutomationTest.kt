package com.drivemusic.android.audio

import com.drivemusic.shared.transition.TransitionFilterRange
import com.drivemusic.shared.transition.TransitionPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seam between the shared curves and the Android engine. If this is wrong, the two platforms
 * read the same `TransitionShape` and produce different transitions — which is the exact failure
 * putting the curves in `:shared` is meant to prevent, so it is worth asserting rather than
 * assuming.
 */
class SlotAutomationTest {
    private val mix = TransitionPreset.MIX.shape
    private val fade = TransitionPreset.FADE.shape

    @Test
    fun aMixStartsWithTheOutgoingTrackWholeAndTheIncomingFilteredAndBassless() {
        val outgoing = SlotAutomation.outgoing(mix, 0.0)
        assertEquals(1.0, outgoing.volume)
        assertEquals(TransitionFilterRange.OPEN_HIGH_FREQUENCY.toDouble(), outgoing.lowPassHz, 1.0)
        assertEquals(0.0, outgoing.bassDb)

        val incoming = SlotAutomation.incoming(mix, 0.0)
        assertEquals(0.0, incoming.volume)
        assertEquals(TransitionFilterRange.CLOSED_FREQUENCY.toDouble(), incoming.highPassHz, 1.0)
        assertEquals(-24.0, incoming.bassDb)
    }

    @Test
    fun aMixEndsWithTheIncomingTrackWholeAndTheOutgoingGone() {
        val outgoing = SlotAutomation.outgoing(mix, 1.0)
        assertEquals(0.0, outgoing.volume)
        assertEquals(TransitionFilterRange.CLOSED_FREQUENCY.toDouble(), outgoing.lowPassHz, 1.0)
        assertEquals(-24.0, outgoing.bassDb)

        val incoming = SlotAutomation.incoming(mix, 1.0)
        assertEquals(1.0, incoming.volume)
        assertEquals(TransitionFilterRange.OPEN_LOW_FREQUENCY.toDouble(), incoming.highPassHz, 1.0)
        assertEquals(0.0, incoming.bassDb)
    }

    /**
     * The depth of a Mix comes from the incoming track being complete and audible while the
     * outgoing one is still there — not from one being turned down.
     */
    @Test
    fun bothTracksAreNearFullLevelThroughTheMiddle() {
        val outgoing = SlotAutomation.outgoing(mix, 0.5)
        val incoming = SlotAutomation.incoming(mix, 0.5)
        assertTrue(outgoing.volume > 0.9, "outgoing sagged to ${outgoing.volume} mid-transition")
        assertTrue(incoming.volume > 0.9, "incoming was only ${incoming.volume} mid-transition")
    }

    /** A plain fade must touch nothing but volume, or it is not a plain fade. */
    @Test
    fun aFadeLeavesEveryFilterOpenThroughout() {
        for (step in 0..10) {
            val t = step / 10.0
            val outgoing = SlotAutomation.outgoing(fade, t)
            val incoming = SlotAutomation.incoming(fade, t)

            assertEquals(TransitionFilterRange.OPEN_HIGH_FREQUENCY.toDouble(), outgoing.lowPassHz, 1.0)
            assertEquals(TransitionFilterRange.OPEN_LOW_FREQUENCY.toDouble(), incoming.highPassHz, 1.0)
            assertEquals(0.0, outgoing.bassDb)
            assertEquals(0.0, incoming.bassDb)
        }
    }

    /**
     * Every timer-driven ramp overshoots by up to one tick. Holding the end of the transition is
     * correct; extrapolating past the last keyframe is not.
     */
    @Test
    fun positionsOutsideTheTransitionAreClamped() {
        assertEquals(SlotAutomation.outgoing(mix, 0.0), SlotAutomation.outgoing(mix, -0.4))
        assertEquals(SlotAutomation.outgoing(mix, 1.0), SlotAutomation.outgoing(mix, 1.7))
        assertEquals(SlotAutomation.incoming(mix, 1.0), SlotAutomation.incoming(mix, 99.0))
    }

    @Test
    fun anOpenSlotIsFullLevelAndUnfiltered() {
        assertEquals(1.0, SlotParameters.open.volume)
        assertEquals(0.0, SlotParameters.open.bassDb)
        assertEquals(0.0, SlotParameters.silent.volume)
    }

    /** Reverb is read from the shape even though nothing applies it yet — see [SlotAutomation]. */
    @Test
    fun theReverbLaneIsReadEvenThoughItIsNotApplied() {
        assertEquals(0.0, SlotAutomation.reverbWet(mix, 0.0))
        assertEquals(35.0, SlotAutomation.reverbWet(mix, 1.0))
        assertEquals(80.0, SlotAutomation.reverbWet(TransitionPreset.RISE.shape, 1.0))
    }
}
