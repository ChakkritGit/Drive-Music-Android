package com.drivemusic.shared.model

import kotlinx.serialization.Serializable

/**
 * Tempo, key, and structural landmarks for one track — everything the auto mix needs to decide
 * how two tracks should meet.
 *
 * [mixInSeconds] and [mixOutSeconds] are the pair that make a transition musical rather than
 * mechanical: where a track is worth coming *into*, and where it is worth leaving. Without them
 * a mix starts the incoming track at 0:00 over its intro and leaves the outgoing one only once
 * it has run out, which is what this app originally did and what both fields exist to fix.
 */
@Serializable
data class TrackAnalysis(
    val fileId: String,
    val bpm: Double? = null,
    val firstBeatSeconds: Double? = null,
    val camelotKey: String? = null,
    val mixInSeconds: Double? = null,
    val mixOutSeconds: Double? = null,
    val durationSeconds: Double? = null,
    val spectralCutoffHz: Double? = null,
    /**
     * A multiplier that brings this track to a common loudness, or null if it was not measured.
     *
     * 1 leaves the track alone. Applied to the slot's output, so it survives the transition
     * automation writing new parameters underneath it.
     */
    val loudnessGain: Double? = null,
    val waveform: List<Float> = emptyList(),
    val version: Int,
) {
    /**
     * How the spectral cutoff reads as a quality tier.
     *
     * The thresholds are where common encoders put their low-pass, so a track landing on one is
     * strong evidence of that setting — but this describes *the spectrum*, not the file's actual
     * bitrate, and a genuinely dark recording with no high end reads low however it was encoded.
     */
    val qualityTier: QualityTier
        get() = when {
            spectralCutoffHz == null -> QualityTier.UNKNOWN
            spectralCutoffHz < 15_000 -> QualityTier.LOW
            spectralCutoffHz < 18_000 -> QualityTier.MEDIUM
            else -> QualityTier.HIGH
        }

    enum class QualityTier {
        /** Cut below ~15kHz — typically 128kbps or lower, or an old, very lossy source. */
        LOW,

        /** ~15–18kHz — roughly 192–256kbps territory. */
        MEDIUM,

        /** Above ~18kHz — 320kbps or lossless; nothing meaningful is missing. */
        HIGH,

        /**
         * No cutoff was found. Not "unknown quality" — the best files are exactly the ones whose
         * spectrum has no wall to find, so this is its own answer rather than a gap.
         */
        UNKNOWN,
    }

    /** Seconds between beats, or null without a tempo. */
    val beatInterval: Double?
        get() = bpm?.takeIf { it > 0 }?.let { 60.0 / it }

    /** How long [bars] bars last at this track's tempo. */
    fun secondsForBars(bars: Int): Double? = beatInterval?.let { it * 4 * bars }

    /** The beat at or before [time], or null without a grid. */
    fun beatOnOrBefore(time: Double): Double? {
        val interval = beatInterval ?: return null
        val first = firstBeatSeconds ?: return null
        if (time <= first) return first
        val beats = ((time - first) / interval).toInt()
        return first + beats * interval
    }
}
