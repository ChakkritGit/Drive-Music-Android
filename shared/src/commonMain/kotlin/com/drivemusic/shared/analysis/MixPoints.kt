package com.drivemusic.shared.analysis

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Where a track is worth mixing into and out of, derived from its loudness envelope.
 *
 * These two live in `commonMain` on purpose. The rest of track analysis — FFT, onset detection,
 * tempo — is platform DSP (Accelerate on iOS, an NDK FFT on Android) and cannot be shared, but
 * these two are plain arithmetic over the envelope array that analysis already produces. They are
 * also the part that decides how a mix *sounds*, so having one implementation rather than two is
 * worth more here than anywhere else in the pipeline.
 */
object MixPoints {
    /**
     * How loud the track has to get, relative to its own typical level, before it counts as
     * having started. Intros are rarely silent — a pad, a filtered loop, a spoken sample — so a
     * threshold against silence finds nothing. Against the track's own median it finds the point
     * where the arrangement actually arrives.
     */
    const val MIX_IN_THRESHOLD = 0.72f

    /**
     * How far below its own typical level the track has to drop for the outro to count as having
     * started. Same scale and value as [MIX_IN_THRESHOLD] — "the arrangement is playing" is one
     * judgement, whichever end it is made at — but separate, since the two ends have no reason to
     * have to move together.
     */
    const val MIX_OUT_THRESHOLD = 0.72f

    /** Never further in than this fraction of the track, whatever the envelope says. */
    const val MAXIMUM_MIX_IN_FRACTION = 0.25

    /** Never earlier than this fraction. A quiet breakdown two thirds in is not the outro. */
    const val MINIMUM_MIX_OUT_FRACTION = 0.5

    /**
     * The track's own "normal" loudness: the median of the part of the envelope that is actually
     * sounding, so however long a quiet opening happens to be does not drag it down.
     *
     * Note what this implies, because it is easy to misread: a track whose tail is merely *quiet*
     * rather than silent has that quiet level counted toward its own median, so the tail becomes
     * the normal level and there is no outro to find. Only a tail that drops out entirely leaves
     * a high reference with little above it.
     */
    private fun referenceLevel(envelope: List<Float>): Float? {
        val sounding = envelope.filter { it > 0.05f }.sorted()
        return if (sounding.isEmpty()) null else sounding[sounding.size / 2]
    }

    /**
     * The first moment the track is properly underway, snapped forward to a bar line.
     *
     * Bars, not beats: the incoming track's *downbeat* is what should land on the outgoing
     * track's downbeat, and arriving on beat 3 is on-grid but still sounds wrong.
     */
    fun mixInPoint(
        envelope: List<Float>,
        duration: Double,
        bpm: Double?,
        firstBeat: Double?,
    ): Double? {
        if (envelope.isEmpty() || duration <= 0) return null
        val reference = referenceLevel(envelope) ?: return null
        val threshold = reference * MIX_IN_THRESHOLD

        val secondsPerBucket = duration / envelope.size
        // Sustained, not instantaneous: a single loud spike in an intro (a riser, a vocal stab)
        // is not the track starting. Requires roughly a second of continuous energy.
        val runLength = max(1, (1.0 / secondsPerBucket).toInt())
        var run = 0
        var startIndex: Int? = null
        for ((index, value) in envelope.withIndex()) {
            if (value >= threshold) {
                run += 1
                if (run >= runLength) {
                    startIndex = index - run + 1
                    break
                }
            } else {
                run = 0
            }
        }

        val start = startIndex ?: return null
        var seconds = start * secondsPerBucket
        seconds = min(seconds, duration * MAXIMUM_MIX_IN_FRACTION)

        // Without a grid the un-snapped point is still far better than 0:00, so return it as-is.
        if (bpm == null || bpm <= 0 || firstBeat == null) return seconds
        val barLength = 60.0 / bpm * 4
        if (seconds <= firstBeat) return firstBeat
        val bars = ceil((seconds - firstBeat) / barLength)
        return firstBeat + bars * barLength
    }

    /**
     * Where the track's last full-strength section ends — the start of its outro, and the point a
     * transition should leave on.
     *
     * Scans backwards, so what it finds is the *last* time the arrangement was at full strength,
     * not the first time it stopped being (which any mid-track breakdown would satisfy). A track
     * that ends hard has its final section running to the last bucket and gets an answer at or
     * near [duration] — the same as having no outro, which is correct.
     *
     * Not snapped to a bar here, unlike [mixInPoint]: the incoming start is used as given, but
     * this is only a proposal that the caller aligns against the outgoing grid alongside the
     * transition length it is planning. Snapping in both places would round twice.
     */
    fun mixOutPoint(envelope: List<Float>, duration: Double): Double? {
        if (envelope.isEmpty() || duration <= 0) return null
        val reference = referenceLevel(envelope) ?: return null
        val threshold = reference * MIX_OUT_THRESHOLD

        val secondsPerBucket = duration / envelope.size
        val runLength = max(1, (1.0 / secondsPerBucket).toInt())
        var run = 0
        var lastStrongIndex: Int? = null
        for (index in envelope.indices.reversed()) {
            if (envelope[index] >= threshold) {
                run += 1
                if (run >= runLength) {
                    // Walked backwards from its end, so the run occupies `index until index + run`
                    // and the outro begins where it stops.
                    lastStrongIndex = index + run
                    break
                }
            } else {
                run = 0
            }
        }

        val strong = lastStrongIndex ?: return null
        val seconds = strong * secondsPerBucket
        return min(max(seconds, duration * MINIMUM_MIX_OUT_FRACTION), duration)
    }
}
