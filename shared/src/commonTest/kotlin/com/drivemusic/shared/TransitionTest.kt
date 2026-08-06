package com.drivemusic.shared

import com.drivemusic.shared.model.TrackAnalysis
import com.drivemusic.shared.transition.TransitionCurve
import com.drivemusic.shared.transition.TransitionFilterRange
import com.drivemusic.shared.transition.TransitionLooping
import com.drivemusic.shared.transition.TransitionPlan
import com.drivemusic.shared.transition.TransitionPreset
import com.drivemusic.shared.transition.TransitionSettings
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransitionCurveTest {
    @Test
    fun holdsTheEndValuesOutsideItsRange() {
        val curve = TransitionCurve.ramp(1.0, 0.0)
        assertEquals(1.0, curve.valueAt(-5.0))
        assertEquals(1.0, curve.valueAt(0.0))
        assertEquals(0.0, curve.valueAt(1.0))
        assertEquals(0.0, curve.valueAt(9.0))
    }

    @Test
    fun interpolatesLinearlyBetweenKeyframes() {
        val curve = TransitionCurve.ramp(0.0, 10.0)
        assertEquals(2.5, curve.valueAt(0.25))
        assertEquals(5.0, curve.valueAt(0.5))
    }

    @Test
    fun detectsConstantLanes() {
        assertTrue(TransitionCurve.constant(0.0).isConstant)
        assertTrue(TransitionCurve.ramp(1.0, 1.0).isConstant)
        assertTrue(!TransitionCurve.ramp(0.0, 1.0).isConstant)
    }

    /**
     * The invariant that makes `valueAt`'s single forward scan correct. Keyframes given out of
     * order must be sorted on the way in — including when they arrive from storage, which is the
     * path the iOS version's synthesized `Codable` skips.
     */
    @Test
    fun keyframesAreSortedOnConstruction() {
        val curve = TransitionCurve(
            listOf(
                TransitionCurve.Keyframe(1.0, 10.0),
                TransitionCurve.Keyframe(0.0, 0.0),
                TransitionCurve.Keyframe(0.5, 5.0),
            )
        )
        assertEquals(listOf(0.0, 0.5, 1.0), curve.keyframes.map { it.t })
        assertEquals(2.5, curve.valueAt(0.25))
    }

    @Test
    fun keyframesAreSortedAfterDecoding() {
        val unsorted = """[{"t":1.0,"value":10.0},{"t":0.0,"value":0.0},{"t":0.5,"value":5.0}]"""
        val curve = Json.decodeFromString(TransitionCurve.serializer(), unsorted)

        assertEquals(listOf(0.0, 0.5, 1.0), curve.keyframes.map { it.t })
        assertEquals(2.5, curve.valueAt(0.25), "a lane decoded out of order reads the wrong segment")
    }

    @Test
    fun roundTripsThroughJson() {
        val original = TransitionPreset.MIX.shape.outgoingBass
        val json = Json.encodeToString(TransitionCurve.serializer(), original)
        assertEquals(original, Json.decodeFromString(TransitionCurve.serializer(), json))
    }

    /** Equal-power lanes must hold summed power near constant, which is why they are not ramps. */
    @Test
    fun equalPowerLanesSumToConstantPower() {
        val down = com.drivemusic.shared.transition.TransitionShape.equalPowerDown
        val up = com.drivemusic.shared.transition.TransitionShape.equalPowerUp
        for (step in 0..10) {
            val t = step / 10.0
            val power = down.valueAt(t) * down.valueAt(t) + up.valueAt(t) * up.valueAt(t)
            assertTrue(abs(power - 1.0) < 0.05, "power at t=$t was $power")
        }
    }
}

class TransitionFilterRangeTest {
    @Test
    fun openPositionIsInaudible() {
        assertEquals(20f, TransitionFilterRange.highPassFrequency(0.0))
        assertEquals(20_000f, TransitionFilterRange.lowPassFrequency(0.0))
    }

    @Test
    fun closedPositionReachesTheClosedFrequency() {
        assertEquals(400f, TransitionFilterRange.highPassFrequency(1.0), 0.01f)
        assertEquals(400f, TransitionFilterRange.lowPassFrequency(1.0), 0.01f)
    }

    /**
     * Geometric, not linear. The midpoint of a linear sweep from 20kHz to 400Hz would be ~10kHz;
     * geometrically it is the geometric mean, ~2.8kHz — which is what makes the sweep sound even.
     */
    @Test
    fun sweepIsGeometricNotLinear() {
        val mid = TransitionFilterRange.lowPassFrequency(0.5)
        assertTrue(mid in 2_500f..3_200f, "expected the geometric mean, got $mid")
    }

    @Test
    fun positionsAreClamped() {
        assertEquals(TransitionFilterRange.highPassFrequency(0.0), TransitionFilterRange.highPassFrequency(-3.0))
        assertEquals(TransitionFilterRange.lowPassFrequency(1.0), TransitionFilterRange.lowPassFrequency(4.0))
    }
}

class TransitionPresetTest {
    @Test
    fun fadeMovesNothingButVolume() {
        val shape = TransitionPreset.FADE.shape
        assertTrue(shape.outgoingLowPass.isConstant)
        assertTrue(shape.incomingHighPass.isConstant)
        assertTrue(shape.outgoingBass.isConstant)
        assertTrue(shape.incomingBass.isConstant)
        assertTrue(shape.outgoingReverb.isConstant)
    }

    /** The bass swap is what makes a mix a mix rather than a crossfade with EQ. */
    @Test
    fun mixSwapsTheBass() {
        val shape = TransitionPreset.MIX.shape
        assertEquals(0.0, shape.outgoingBass.valueAt(0.0))
        assertTrue(shape.outgoingBass.valueAt(1.0) <= -24.0)
        assertTrue(shape.incomingBass.valueAt(0.0) <= -24.0)
        assertEquals(0.0, shape.incomingBass.valueAt(1.0))
    }

    /**
     * The low end is never shared at full strength — two tracks both carrying their bass is the
     * muddiness people hear as "just a crossfade with EQ".
     *
     * Stated as a budget on the *sum* rather than "one of them is always cut", because the swap is
     * deliberately a handover rather than a cut: over its middle tenth both tracks pass through
     * -9dB at the crossover, which is a controlled dip, not two tracks at full bass.
     */
    @Test
    fun theLowEndIsNeverSharedAtFullStrength() {
        val shape = TransitionPreset.MIX.shape
        for (step in 0..40) {
            val t = step / 40.0
            val outgoing = shape.outgoingBass.valueAt(t)
            val incoming = shape.incomingBass.valueAt(t)
            assertTrue(
                outgoing + incoming <= -18.0,
                "the two tracks' bass summed to ${outgoing + incoming}dB at t=$t"
            )
            assertTrue(
                outgoing < 0.0 || incoming < 0.0,
                "both tracks were at full bass at t=$t"
            )
        }
    }

    @Test
    fun riseIsTheOnlyPresetThatLoops() {
        assertEquals(TransitionLooping.OUTGOING_ONE_BAR, TransitionPreset.RISE.shape.looping)
        assertEquals(TransitionLooping.NONE, TransitionPreset.FADE.shape.looping)
        assertEquals(TransitionLooping.NONE, TransitionPreset.MIX.shape.looping)
        assertEquals(TransitionLooping.NONE, TransitionPreset.BLEND.shape.looping)
    }

    @Test
    fun everyPresetIsRecoverableFromItsShape() {
        for (preset in TransitionPreset.entries) {
            assertEquals(preset, TransitionPreset.matching(preset.shape))
        }
    }

    @Test
    fun anEditedShapeMatchesNoPreset() {
        val edited = TransitionPreset.MIX.shape.copy(outgoingReverb = TransitionCurve.ramp(0.0, 99.0))
        assertNull(TransitionPreset.matching(edited))
    }
}

class TransitionPlanTest {
    private fun analysis(bpm: Double?, duration: Double = 200.0, mixIn: Double? = null) =
        TrackAnalysis(
            fileId = "x",
            bpm = bpm,
            firstBeatSeconds = 0.1,
            mixInSeconds = mixIn,
            durationSeconds = duration,
            version = 1,
        )

    @Test
    fun autoMixOffGivesAPlainFade() {
        val plan = TransitionPlan.resolve(
            settings = TransitionSettings.AUTO,
            outgoing = null, incoming = null,
            outgoingDuration = 200.0, fallbackDuration = 5.0,
            autoMixEnabled = false, beatmatchEnabledByDefault = true,
        )
        assertEquals(TransitionPreset.FADE.shape, plan.shape)
    }

    @Test
    fun withoutATempoTheGlobalCrossfadeLengthIsUsed() {
        val plan = TransitionPlan.resolve(
            settings = TransitionSettings.AUTO,
            outgoing = null, incoming = null,
            outgoingDuration = 200.0, fallbackDuration = 5.0,
            autoMixEnabled = true, beatmatchEnabledByDefault = true,
        )
        assertEquals(5.0, plan.duration)
    }

    /** 4 bars at 120 BPM is 8 seconds — what every DJ tool defaults to for a mix. */
    @Test
    fun aTempoTurnsBarsIntoSeconds() {
        val plan = TransitionPlan.resolve(
            settings = TransitionSettings.AUTO,
            outgoing = analysis(bpm = 120.0), incoming = analysis(bpm = 120.0),
            outgoingDuration = 200.0, fallbackDuration = 5.0,
            autoMixEnabled = true, beatmatchEnabledByDefault = true,
        )
        assertEquals(8.0, plan.duration, 0.001)
    }

    /**
     * A bar count is a musical length, not a fraction of a song: 16 bars at 70 BPM is nearly a
     * minute, which on a short interlude would be longer than the track it is leaving.
     */
    @Test
    fun theTransitionIsCappedAtAThirdOfTheOutgoingTrack() {
        val plan = TransitionPlan.resolve(
            settings = TransitionSettings(bars = 16),
            outgoing = analysis(bpm = 70.0, duration = 30.0), incoming = analysis(bpm = 70.0),
            outgoingDuration = 30.0, fallbackDuration = 5.0,
            autoMixEnabled = true, beatmatchEnabledByDefault = true,
        )
        assertEquals(10.0, plan.duration, 0.001)
    }

    @Test
    fun beatmatchStretchesWithinTheLimit() {
        // 124 into 120 is a 3.2% stretch — inside the 6% limit.
        val plan = TransitionPlan.resolve(
            settings = TransitionSettings.AUTO,
            outgoing = analysis(bpm = 124.0), incoming = analysis(bpm = 120.0),
            outgoingDuration = 200.0, fallbackDuration = 5.0,
            autoMixEnabled = true, beatmatchEnabledByDefault = true,
        )
        assertTrue(abs(plan.incomingRate - 124.0f / 120.0f) < 0.0001f)
    }

    @Test
    fun tracksTooFarApartAreNotStretched() {
        // 140 into 120 is 17% — well past the point the artifacts are audible.
        val plan = TransitionPlan.resolve(
            settings = TransitionSettings.AUTO,
            outgoing = analysis(bpm = 140.0), incoming = analysis(bpm = 120.0),
            outgoingDuration = 200.0, fallbackDuration = 5.0,
            autoMixEnabled = true, beatmatchEnabledByDefault = true,
        )
        assertEquals(1f, plan.incomingRate)
    }

    @Test
    fun beatmatchOffNeverStretches() {
        val plan = TransitionPlan.resolve(
            settings = TransitionSettings(beatmatchEnabled = false),
            outgoing = analysis(bpm = 124.0), incoming = analysis(bpm = 120.0),
            outgoingDuration = 200.0, fallbackDuration = 5.0,
            autoMixEnabled = true, beatmatchEnabledByDefault = true,
        )
        assertEquals(1f, plan.incomingRate)
    }

    /** The incoming track starts where it arrives, not at frame 0 over its intro. */
    @Test
    fun theIncomingTrackStartsAtItsMixInPoint() {
        val plan = TransitionPlan.resolve(
            settings = TransitionSettings.AUTO,
            outgoing = analysis(bpm = 120.0), incoming = analysis(bpm = 120.0, mixIn = 18.0),
            outgoingDuration = 200.0, fallbackDuration = 5.0,
            autoMixEnabled = true, beatmatchEnabledByDefault = true,
        )
        assertEquals(18.0, plan.incomingStartSeconds)
    }

    @Test
    fun aHandPlacedIncomingStartWins() {
        val plan = TransitionPlan.resolve(
            settings = TransitionSettings(incomingStartSeconds = 42.0),
            outgoing = analysis(bpm = 120.0), incoming = analysis(bpm = 120.0, mixIn = 18.0),
            outgoingDuration = 200.0, fallbackDuration = 5.0,
            autoMixEnabled = true, beatmatchEnabledByDefault = true,
        )
        assertEquals(42.0, plan.incomingStartSeconds)
    }

    /** A loop is measured in bars, so without a tempo there is nothing to measure. */
    @Test
    fun loopingNeedsBothATempoAndAStartPoint() {
        val withoutStart = TransitionPlan.resolve(
            settings = TransitionSettings(shape = TransitionPreset.RISE.shape),
            outgoing = analysis(bpm = 120.0), incoming = analysis(bpm = 120.0),
            outgoingDuration = 200.0, fallbackDuration = 5.0,
            autoMixEnabled = true, beatmatchEnabledByDefault = true,
        )
        assertNull(withoutStart.outgoingLoop)

        val withStart = TransitionPlan.resolve(
            settings = TransitionSettings(shape = TransitionPreset.RISE.shape, outgoingStartSeconds = 100.0),
            outgoing = analysis(bpm = 120.0), incoming = analysis(bpm = 120.0),
            outgoingDuration = 200.0, fallbackDuration = 5.0,
            autoMixEnabled = true, beatmatchEnabledByDefault = true,
        )
        val loop = assertNotNull(withStart.outgoingLoop)
        // One bar at 120 BPM is 2 seconds, ending where the transition starts.
        assertEquals(98.0, loop.start, 0.001)
        assertEquals(100.0, loop.endInclusive, 0.001)
    }

    @Test
    fun anUnanalyzedPairStillProducesAUsablePlan() {
        val plan = TransitionPlan.resolve(
            settings = TransitionSettings.AUTO,
            outgoing = null, incoming = null,
            outgoingDuration = null, fallbackDuration = 6.0,
            autoMixEnabled = true, beatmatchEnabledByDefault = true,
        )
        assertEquals(6.0, plan.duration)
        assertEquals(0.0, plan.incomingStartSeconds)
        assertEquals(1f, plan.incomingRate)
        assertNull(plan.startSeconds)
        assertNull(plan.outgoingLoop)
    }
}
