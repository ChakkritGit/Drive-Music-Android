package com.drivemusic.shared.analysis

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tempo, key and loudness shape for one track — a port of `TrackAnalyzer.swift`, minus the
 * decoding, which is the only genuinely platform-specific part.
 *
 * Everything here takes samples it is given and returns numbers. That is what lets it live in
 * `commonMain` beside [MixPoints], which consumes its envelope: the two halves of the decision
 * about how a mix sounds are in one place, and iOS and Android are provably running the same
 * arithmetic rather than two implementations that happen to agree today.
 */
object TrackAnalyzer {
    /**
     * Bumped whenever anything here changes what a stored result would be, so old rows are
     * recomputed rather than silently believed.
     *
     * 8 is the first version past iOS's 7: the onset gate and the interpolated tempo peak both
     * change the numbers this produces, so results stored by 7 are not results this would produce.
     */
    const val VERSION = 8

    /**
     * Everything except the spectral cutoff runs at this rate. Tempo, key and envelope all live
     * well below 11kHz, and a quarter of the samples is a quarter of the work.
     */
    const val ANALYSIS_SAMPLE_RATE = 22_050.0

    /** The cutoff hunt needs the top of the spectrum, so it needs a rate that still has one. */
    const val CUTOFF_SAMPLE_RATE = 44_100.0

    const val FRAME_SIZE = 2048
    const val HOP_SIZE = 512

    /** How far the best autocorrelation peak must stand above the average to be believed. */
    const val MINIMUM_TEMPO_CONFIDENCE = 1.35

    const val MINIMUM_BPM = 70.0
    const val MAXIMUM_BPM = 180.0

    /** Buckets in the stored waveform. Enough to draw, small enough to keep in a row. */
    const val WAVEFORM_RESOLUTION = 400

    /** Frames per second of the onset envelope — every lag below is in those frames. */
    const val FLUX_RATE = ANALYSIS_SAMPLE_RATE / HOP_SIZE

    // MARK: - Waveform

    /**
     * Peak-per-bucket envelope, normalised so the loudest point is 1.
     *
     * Peak rather than RMS because this is drawn as well as measured, and a peak envelope is what
     * makes a waveform look like the shape people recognise; RMS reads as a flat sausage.
     */
    fun envelope(samples: FloatArray, buckets: Int = WAVEFORM_RESOLUTION): List<Float> {
        if (buckets <= 0 || samples.isEmpty()) return emptyList()
        val bucketSize = max(1, samples.size / buckets)
        val result = ArrayList<Float>(buckets)
        var index = 0
        while (index < samples.size && result.size < buckets) {
            val end = min(index + bucketSize, samples.size)
            var peak = 0f
            for (position in index until end) {
                val magnitude = abs(samples[position])
                if (magnitude > peak) peak = magnitude
            }
            result += peak
            index = end
        }
        val maximum = result.maxOrNull() ?: 0f
        return if (maximum > 0f) result.map { it / maximum } else result
    }

    // MARK: - Onsets

    /**
     * Spectral flux: per frame, the total *increase* in magnitude per frequency bin since the
     * previous frame. Its peaks line up with note and drum attacks, which is what a beat grid is
     * ultimately made of.
     */
    fun spectralFlux(samples: FloatArray): FloatArray {
        val halfSize = FRAME_SIZE / 2
        if (samples.size < FRAME_SIZE) return FloatArray(0)

        val window = Fft.hannWindow(FRAME_SIZE)
        val windowed = FloatArray(FRAME_SIZE)
        val real = FloatArray(FRAME_SIZE)
        val imaginary = FloatArray(FRAME_SIZE)
        val magnitudes = FloatArray(halfSize)
        var previous = FloatArray(halfSize)

        val frames = (samples.size - FRAME_SIZE) / HOP_SIZE + 1
        val flux = FloatArray(frames)

        var frame = 0
        var start = 0
        while (start + FRAME_SIZE <= samples.size) {
            for (i in 0 until FRAME_SIZE) windowed[i] = samples[start + i] * window[i]
            Fft.magnitudes(windowed, real, imaginary, magnitudes)

            var frameFlux = 0f
            for (bin in 0 until halfSize) {
                val rise = magnitudes[bin] - previous[bin]
                if (rise > 0f) frameFlux += rise
            }
            flux[frame] = frameFlux
            magnitudes.copyInto(previous)
            frame++
            start += HOP_SIZE
        }

        return normalizedAndSmoothed(flux)
    }

    /**
     * Subtracts a local moving average and clips at zero, then normalises.
     *
     * Without this, a track that gets louder partway through swamps the autocorrelation with its
     * own dynamics rather than its rhythm — what matters is each onset relative to its neighbours,
     * not absolutely.
     */
    private fun normalizedAndSmoothed(flux: FloatArray): FloatArray {
        if (flux.isEmpty()) return flux
        val radius = 10
        val result = FloatArray(flux.size)
        for (index in flux.indices) {
            val lower = max(0, index - radius)
            val upper = min(flux.size - 1, index + radius)
            var sum = 0f
            for (position in lower..upper) sum += flux[position]
            val localMean = sum / (upper - lower + 1)
            result[index] = max(0f, flux[index] - localMean)
        }
        val maximum = result.maxOrNull() ?: 0f
        if (maximum <= 0f) return result
        for (index in result.indices) result[index] /= maximum
        return result
    }

    // MARK: - Tempo

    data class Tempo(val bpm: Double?, val firstBeatSeconds: Double?)

    /**
     * Autocorrelation over the plausible beat-period range, then phase. Returns nulls when nothing
     * stands out clearly enough — see [MINIMUM_TEMPO_CONFIDENCE].
     */
    fun tempoAndPhase(flux: FloatArray): Tempo {
        if (flux.size <= 64) return Tempo(null, null)
        if (!hasEnoughOnsets(flux)) return Tempo(null, null)

        val minimumLag = (60 / MAXIMUM_BPM * FLUX_RATE).roundToInt()
        val maximumLag = (60 / MINIMUM_BPM * FLUX_RATE).roundToInt()
        if (maximumLag <= minimumLag || maximumLag >= flux.size) return Tempo(null, null)

        val scores = DoubleArray(maximumLag - minimumLag + 1)
        var bestLag = minimumLag
        var bestScore = Double.NEGATIVE_INFINITY
        var total = 0.0
        for (lag in minimumLag..maximumLag) {
            var sum = 0.0
            var index = 0
            while (index + lag < flux.size) {
                sum += flux[index].toDouble() * flux[index + lag].toDouble()
                index++
            }
            val score = sum / (flux.size - lag)
            scores[lag - minimumLag] = score
            total += score
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        val mean = total / scores.size
        if (mean <= 0 || bestScore / mean < MINIMUM_TEMPO_CONFIDENCE) return Tempo(null, null)

        var bpm = 60 * FLUX_RATE / refinedLag(scores, bestLag - minimumLag, minimumLag)
        // Octave correction. Half- and double-tempo are both real periodicities of the same
        // signal, so the raw winner is often musically the wrong one of the pair; pulling toward
        // 90–160 BPM picks the reading a listener would call "the tempo" for nearly all popular
        // music, which is the only music this feature is aimed at.
        while (bpm < 90 && bpm * 2 <= MAXIMUM_BPM) bpm *= 2
        while (bpm > 160 && bpm / 2 >= MINIMUM_BPM) bpm /= 2

        val periodFrames = 60 * FLUX_RATE / bpm
        return Tempo(bpm, beatPhase(flux, periodFrames))
    }

    /**
     * The peak lag, refined below the resolution of the lag grid itself.
     *
     * Lags are whole frames, and around 128 BPM one frame is about six BPM — so the raw winner is
     * the *nearest representable* tempo, not the tempo. That is visible twice over: the badge
     * reads 129 for a 128 BPM track, and, far worse, the beat grid built from the rounded period
     * drifts. At 13ms of error per beat a four-minute track ends several seconds out of phase with
     * itself, which is exactly the alignment beatmatching depends on.
     *
     * Fitting a parabola through the winning score and its two neighbours puts the peak where the
     * underlying continuous correlation actually peaks. Three points and one divide.
     *
     * The iOS analyser rounds instead, and drifts the same way — this is the one place the two
     * deliberately differ, and it is the shared implementation that is right.
     */
    private fun refinedLag(scores: DoubleArray, peakIndex: Int, minimumLag: Int): Double {
        val lag = (peakIndex + minimumLag).toDouble()
        if (peakIndex <= 0 || peakIndex >= scores.size - 1) return lag
        val before = scores[peakIndex - 1]
        val at = scores[peakIndex]
        val after = scores[peakIndex + 1]
        val curvature = before - 2 * at + after
        // A flat or upward-curving neighbourhood is not a peak to refine; leaving it alone is the
        // only safe answer, and the confidence gate above has already accepted this lag.
        if (curvature >= 0) return lag
        val offset = 0.5 * (before - after) / curvature
        return if (offset > -1 && offset < 1) lag + offset else lag
    }

    /**
     * Whether there are enough onsets here to be talking about a tempo at all.
     *
     * The flux is normalised to a maximum of 1, so a track with no attacks still produces a
     * confident-looking envelope: one accidental spike becomes 1.0 and the autocorrelation finds
     * a periodicity in what is essentially noise. A held tone measured this way reports a tempo of
     * around 143 BPM, which is a number a listener would have no way to know is meaningless.
     *
     * A pulse is not one loud frame, it is many. Requiring a minimum count of frames carrying real
     * onset energy separates the two cleanly: a click track has hundreds, a drone has one.
     *
     * This gate is not in the iOS analyser, which has the same weakness — see the note in the
     * commit that added this.
     */
    private fun hasEnoughOnsets(flux: FloatArray): Boolean {
        var significant = 0
        for (value in flux) {
            if (value > ONSET_FLOOR) significant++
        }
        return significant >= MINIMUM_ONSETS
    }

    /** Relative to the normalised maximum of 1. */
    private const val ONSET_FLOOR = 0.2f

    /**
     * Fewer beats than this is not a tempo, it is a coincidence — and the autocorrelation over a
     * plausible-period range will always find *something*.
     */
    private const val MINIMUM_ONSETS = 8

    /**
     * Tries every offset within one beat period and keeps whichever makes the onsets sum highest —
     * the alignment where the grid's beats land on actual attacks rather than between them.
     * Returned in seconds from the start of the file.
     */
    fun beatPhase(flux: FloatArray, periodFrames: Double): Double? {
        if (periodFrames < 1 || flux.size <= periodFrames.toInt()) return null
        var bestOffset = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (offset in 0 until periodFrames.roundToInt()) {
            var score = 0.0
            var position = offset.toDouble()
            while (position < flux.size) {
                score += flux[position.toInt()].toDouble()
                position += periodFrames
            }
            if (score > bestScore) {
                bestScore = score
                bestOffset = offset
            }
        }
        return bestOffset / FLUX_RATE
    }

    // MARK: - Key

    /**
     * Krumhansl–Schmuckler key profiles: how strongly each of the 12 pitch classes tends to be
     * present in a piece in a given key, derived from listener ratings. Correlating a track's own
     * pitch-class distribution against all 24 rotations of these is the standard key-finding
     * method, and cheap once the chroma vector exists.
     */
    private val MAJOR_PROFILE = doubleArrayOf(
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88,
    )
    private val MINOR_PROFILE = doubleArrayOf(
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17,
    )

    /**
     * Camelot wheel positions by pitch class (C, C#, D, …). Two lookup tables rather than the
     * arithmetic that generates them: the wheel's ordering is a fixed fact about the notation, and
     * a table is impossible to get subtly wrong the way a modular formula is.
     */
    private val MAJOR_CAMELOT = listOf(
        "8B", "3B", "10B", "5B", "12B", "7B", "2B", "9B", "4B", "11B", "6B", "1B",
    )
    private val MINOR_CAMELOT = listOf(
        "5A", "12A", "7A", "2A", "9A", "4A", "11A", "6A", "1A", "8A", "3A", "10A",
    )

    fun detectKey(samples: FloatArray): String? {
        val chroma = chromaVector(samples)
        if (chroma.none { it > 0 }) return null

        var bestScore = Double.NEGATIVE_INFINITY
        var bestKey: String? = null
        for (tonic in 0 until 12) {
            val rotated = DoubleArray(12) { chroma[(it + tonic) % 12] }
            val majorScore = correlation(rotated, MAJOR_PROFILE)
            if (majorScore > bestScore) {
                bestScore = majorScore
                bestKey = MAJOR_CAMELOT[tonic]
            }
            val minorScore = correlation(rotated, MINOR_PROFILE)
            if (minorScore > bestScore) {
                bestScore = minorScore
                bestKey = MINOR_CAMELOT[tonic]
            }
        }
        // A flat correlation means the track has no clear tonal centre (percussion-only, heavily
        // processed, spoken word) — no key is a better answer than an arbitrary one.
        return if (bestScore > 0.6) bestKey else null
    }

    /**
     * Folds the spectrum into 12 pitch classes across the whole track. Each bin's magnitude is
     * added to whichever semitone its frequency falls on, over the range where pitch is actually
     * carried (roughly A1 to A7).
     */
    fun chromaVector(samples: FloatArray): DoubleArray {
        val chroma = DoubleArray(12)
        val halfSize = FRAME_SIZE / 2
        if (samples.size < FRAME_SIZE) return chroma

        val window = Fft.hannWindow(FRAME_SIZE)
        val windowed = FloatArray(FRAME_SIZE)
        val real = FloatArray(FRAME_SIZE)
        val imaginary = FloatArray(FRAME_SIZE)
        val magnitudes = FloatArray(halfSize)
        val binWidth = ANALYSIS_SAMPLE_RATE / FRAME_SIZE

        // Every 4th frame — key is a property of the whole track, so a quarter of the frames is
        // plenty and cuts the most expensive part of analysis to a quarter of its cost.
        val stride = HOP_SIZE * 4
        var start = 0
        while (start + FRAME_SIZE <= samples.size) {
            for (i in 0 until FRAME_SIZE) windowed[i] = samples[start + i] * window[i]
            Fft.magnitudes(windowed, real, imaginary, magnitudes)

            for (bin in 1 until halfSize) {
                val frequency = bin * binWidth
                if (frequency < 55 || frequency > 3520) continue
                // Semitones above A1 (55Hz), folded to a pitch class. +3 shifts A to index 9 so
                // index 0 lands on C, matching the profile and Camelot tables above.
                val semitone = 12 * log2(frequency / 55)
                val pitchClass = (semitone.roundToInt() + 9) % 12
                chroma[(pitchClass + 12) % 12] += magnitudes[bin].toDouble()
            }
            start += stride
        }

        val total = chroma.sum()
        if (total <= 0) return chroma
        for (index in chroma.indices) chroma[index] /= total
        return chroma
    }

    /**
     * Pearson correlation — the standard scoring for key profiles, and unlike a plain dot product
     * it is insensitive to how loud the track is or how the chroma was normalised.
     */
    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val meanA = a.average()
        val meanB = b.average()
        var covariance = 0.0
        var varianceA = 0.0
        var varianceB = 0.0
        for (index in a.indices) {
            val deltaA = a[index] - meanA
            val deltaB = b[index] - meanB
            covariance += deltaA * deltaB
            varianceA += deltaA * deltaA
            varianceB += deltaB * deltaB
        }
        if (varianceA <= 0 || varianceB <= 0) return 0.0
        return covariance / sqrt(varianceA * varianceB)
    }

    // MARK: - Spectral cutoff

    /**
     * Where the track's spectrum stops — the low-pass wall a lossy encoder left behind — given a
     * long-term average magnitude spectrum.
     *
     * Walks *down* from Nyquist and returns the first frequency whose energy rises above a floor
     * set relative to the track's own mid-band level. Averaging over the whole file rather than
     * sampling a few frames matters: any single frame can be quiet or dull, but a hard encoder
     * cutoff is the one feature that holds across every frame of the file.
     *
     * Null when there is no wall to find — the spectrum runs to Nyquist, or the track is too quiet
     * to say anything about.
     */
    fun spectralCutoff(spectrum: FloatArray, sampleRate: Double = CUTOFF_SAMPLE_RATE): Double? {
        if (spectrum.isEmpty()) return null
        val binWidth = sampleRate / FRAME_SIZE

        // Reference level: the median of the 200Hz–5kHz band, where essentially all music has
        // energy. Median rather than mean, so a single resonant peak does not drag the reference
        // up and make everything above it look quiet by comparison.
        val referenceLow = (200 / binWidth).toInt()
        val referenceHigh = min(spectrum.size - 1, (5_000 / binWidth).toInt())
        if (referenceHigh <= referenceLow) return null
        val band = spectrum.copyOfRange(referenceLow, referenceHigh + 1).sortedArray()
        val reference = band[band.size / 2]
        if (reference <= 0f) return null

        val floor = reference * CUTOFF_RATIO
        var bin = spectrum.size - 1
        while (bin > referenceHigh) {
            if (spectrum[bin] > floor) {
                val frequency = bin * binWidth
                // A "cutoff" at Nyquist is not a cutoff, it is a file with content all the way up.
                return if (frequency >= sampleRate / 2 * 0.95) null else frequency
            }
            bin--
        }
        return null
    }

    /** How far below the mid-band median counts as "nothing here". */
    private const val CUTOFF_RATIO = 0.0025f
}
