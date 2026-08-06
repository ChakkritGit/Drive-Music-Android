package com.drivemusic.shared

import com.drivemusic.shared.analysis.MixPoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported alongside the code, from the Swift suite that covers the same logic. Envelopes are built
 * by hand so the expected answer is arithmetic rather than a guess — and so a divergence between
 * the two platforms shows up as a failing test rather than as a mix that sounds subtly different
 * on one of them.
 */
class MixPointsTest {
    /** 400 buckets over 200 seconds = 0.5s each, the shape a real analysis produces. */
    private val duration = 200.0

    private fun envelope(vararg sections: Pair<Float, Int>): List<Float> =
        sections.flatMap { (level, count) -> List(count) { level } }

    @Test
    fun findsWhereTheOutroBegins() {
        // 300 buckets loud (0-150s), then 100 buckets of quiet outro (150-200s).
        val mixOut = MixPoints.mixOutPoint(envelope(1.0f to 300, 0.2f to 100), duration)
        assertNotNull(mixOut)
        assertTrue(kotlin.math.abs(mixOut - 150.0) < 1.5, "expected ~150s, got $mixOut")
        assertTrue(mixOut < duration - 40, "must leave well before the end, that is the point")
    }

    @Test
    fun hardEndingReportsTheEnd() {
        val mixOut = MixPoints.mixOutPoint(envelope(1.0f to 400), duration)
        assertNotNull(mixOut)
        assertTrue(kotlin.math.abs(mixOut - duration) < 1.5, "expected ~200s, got $mixOut")
    }

    @Test
    fun midTrackBreakdownIsNotMistakenForTheOutro() {
        // Loud 0-75s, breakdown 75-100s, loud again 100-175s, outro 175-200s.
        val mixOut = MixPoints.mixOutPoint(
            envelope(1.0f to 150, 0.2f to 50, 1.0f to 150, 0.2f to 50), duration
        )
        assertNotNull(mixOut)
        assertTrue(kotlin.math.abs(mixOut - 175.0) < 1.5, "expected ~175s, got $mixOut")
    }

    @Test
    fun isolatedSpikeInTheOutroIsIgnored() {
        val mixOut = MixPoints.mixOutPoint(
            envelope(1.0f to 200, 0.2f to 100, 1.0f to 1, 0.2f to 99), duration
        )
        assertNotNull(mixOut)
        assertTrue(kotlin.math.abs(mixOut - 100.0) < 1.5, "expected ~100s, got $mixOut")
    }

    /**
     * The tail here is true silence, not merely quiet, and that distinction is what makes this
     * clamp reachable at all — see [MixPoints] on how the reference level is derived.
     */
    @Test
    fun neverMixesOutBeforeHalfway() {
        val mixOut = MixPoints.mixOutPoint(envelope(1.0f to 40, 0.0f to 360), duration)
        assertEquals(duration / 2, mixOut)
    }

    @Test
    fun silentTrackHasNoMixOutPoint() {
        assertNull(MixPoints.mixOutPoint(List(400) { 0.0f }, duration))
        assertNull(MixPoints.mixOutPoint(emptyList(), duration))
        assertNull(MixPoints.mixOutPoint(listOf(1.0f, 1.0f, 1.0f), 0.0))
    }

    @Test
    fun mixInSkipsTheIntro() {
        // Quiet intro 0-50s, then the track proper.
        val mixIn = MixPoints.mixInPoint(envelope(0.1f to 100, 1.0f to 300), duration, null, null)
        assertNotNull(mixIn)
        assertTrue(kotlin.math.abs(mixIn - 50.0) < 1.5, "expected ~50s, got $mixIn")
    }

    /**
     * A track that only arrives at 150s (75% in) is capped at 25%.
     *
     * The intro here is true silence, not merely quiet, for the same reason the mix-out clamp test
     * needs it: a merely-quiet intro counts toward the median and becomes the track's own normal
     * level, so there is nothing to skip past and the cap is never reached.
     */
    @Test
    fun mixInNeverSkipsMoreThanAQuarterOfTheTrack() {
        val mixIn = MixPoints.mixInPoint(envelope(0.0f to 300, 1.0f to 100), duration, null, null)
        assertNotNull(mixIn)
        assertEquals(duration * MixPoints.MAXIMUM_MIX_IN_FRACTION, mixIn)
    }
}
