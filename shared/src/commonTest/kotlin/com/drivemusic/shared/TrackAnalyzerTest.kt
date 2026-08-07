package com.drivemusic.shared

import com.drivemusic.shared.analysis.Fft
import com.drivemusic.shared.analysis.TrackAnalyzer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackAnalyzerTest {

    /** A percussive hit: a burst of noise-like content with a fast decay. */
    private fun addHit(into: FloatArray, at: Int, seed: Int) {
        var random = seed
        val length = (TrackAnalyzer.ANALYSIS_SAMPLE_RATE * 0.05).toInt()
        for (offset in 0 until length) {
            val index = at + offset
            if (index >= into.size) return
            // A cheap deterministic noise source — the detector cares about broadband energy
            // arriving suddenly, not about the noise being statistically good.
            random = random * 1_103_515_245 + 12_345
            val noise = ((random ushr 16) and 0x7fff) / 16_384f - 1f
            val decay = exp(-8.0 * offset / length).toFloat()
            into[index] += noise * decay * 0.8f
        }
    }

    private fun clickTrack(bpm: Double, seconds: Double): FloatArray {
        val rate = TrackAnalyzer.ANALYSIS_SAMPLE_RATE
        val samples = FloatArray((rate * seconds).toInt())
        // A quiet tone underneath, so the track is not pure silence between hits — silence makes
        // the flux trivially clean in a way real music never is.
        for (index in samples.indices) {
            samples[index] = (0.05 * sin(2 * PI * 220 * index / rate)).toFloat()
        }
        val interval = 60.0 / bpm * rate
        var position = 0.0
        var beat = 0
        while (position < samples.size) {
            addHit(samples, position.toInt(), seed = 7 + beat)
            position += interval
            beat++
        }
        return samples
    }

    @Test
    fun detectsTheTempoOfAClickTrack() {
        val flux = TrackAnalyzer.spectralFlux(clickTrack(bpm = 128.0, seconds = 30.0))
        val tempo = TrackAnalyzer.tempoAndPhase(flux)

        val bpm = assertNotNull(tempo.bpm, "a steady 128 BPM pulse should be detected")
        // Tight, because the peak is interpolated between lags rather than rounded to one. On
        // the raw lag grid the nearest representable answer here is 129.2 — a whole BPM out, and
        // enough error in the period to drift the beat grid seconds over a full track.
        assertTrue(abs(bpm - 128.0) < 0.5, "expected 128 BPM, got $bpm")
    }

    @Test
    fun octaveCorrectionHalvesVeryFastPulses() {
        // 170 BPM is above the 160 the correction pulls back from, and half of it is inside the
        // search range, so the reported tempo is the one a listener would call the tempo.
        //
        // Note the direction: 60 BPM would *not* be doubled to 120, because a 60 BPM period is
        // outside the lag range searched at all — the correction only reinterprets tempos that
        // were found, it does not extend the range.
        val flux = TrackAnalyzer.spectralFlux(clickTrack(bpm = 170.0, seconds = 30.0))
        val bpm = assertNotNull(TrackAnalyzer.tempoAndPhase(flux).bpm)
        assertTrue(abs(bpm - 85.0) < 0.5, "expected 170 BPM to be reported as 85, got $bpm")
    }

    @Test
    fun theBeatGridLandsOnOnsetsRatherThanBetweenThem() {
        val samples = clickTrack(bpm = 120.0, seconds = 30.0)
        val flux = TrackAnalyzer.spectralFlux(samples)
        val tempo = TrackAnalyzer.tempoAndPhase(flux)
        val firstBeat = assertNotNull(tempo.firstBeatSeconds)
        val bpm = assertNotNull(tempo.bpm)

        // Asserted as what the phase search is actually for, rather than as a distance from where
        // the hits were written. The detected onset lags the hit that caused it by up to a frame
        // — 2048 samples is 93ms — and the grid is quantised to 23ms hops on top of that, so a
        // comparison against the source timing measures the analysis window, not the alignment.
        //
        // What matters is that the grid sits on the onsets and not between them: sampling the
        // onset envelope on the beat should score far higher than sampling it off the beat.
        val period = 60.0 / bpm
        fun scoreAt(offsetSeconds: Double): Double {
            var score = 0.0
            var time = firstBeat + offsetSeconds
            while (time < flux.size / TrackAnalyzer.FLUX_RATE) {
                score += flux[(time * TrackAnalyzer.FLUX_RATE).toInt()].toDouble()
                time += period
            }
            return score
        }

        val onBeat = scoreAt(0.0)
        val offBeat = scoreAt(period / 2)
        assertTrue(onBeat > offBeat * 10, "on-beat $onBeat should dwarf off-beat $offBeat")
    }

    @Test
    fun refusesToGuessATempoForUnpulsedAudio() {
        // A pure tone has onsets nowhere. A confident answer here would be worse than none: it
        // would shape every mix out of the track.
        val rate = TrackAnalyzer.ANALYSIS_SAMPLE_RATE
        val samples = FloatArray((rate * 20).toInt()) {
            (0.5 * sin(2 * PI * 440 * it / rate)).toFloat()
        }
        assertNull(TrackAnalyzer.tempoAndPhase(TrackAnalyzer.spectralFlux(samples)).bpm)
    }

    @Test
    fun envelopeIsNormalisedAndFollowsLoudness() {
        val rate = TrackAnalyzer.ANALYSIS_SAMPLE_RATE.toInt()
        val samples = FloatArray(rate * 4) { index ->
            val amplitude = if (index < rate * 2) 0.2f else 1.0f
            amplitude * sin(2 * PI * 440 * index / rate).toFloat()
        }
        val envelope = TrackAnalyzer.envelope(samples, buckets = 8)

        assertEquals(8, envelope.size)
        assertTrue(envelope.max() <= 1.0f + 1e-4f, "the peak should be normalised to 1")
        assertTrue(envelope.first() < 0.5f, "the quiet half should read quiet")
        assertTrue(envelope.last() > 0.9f, "the loud half should read loud")
    }

    @Test
    fun detectsTheKeyOfAMajorChord() {
        val rate = TrackAnalyzer.ANALYSIS_SAMPLE_RATE
        // A C major triad: C4, E4, G4. Held long enough that the chroma has plenty to average.
        val frequencies = listOf(261.63, 329.63, 392.00)
        val samples = FloatArray((rate * 10).toInt()) { index ->
            frequencies.sumOf { 0.3 * sin(2 * PI * it * index / rate) }.toFloat()
        }
        val key = assertNotNull(TrackAnalyzer.detectKey(samples), "a held triad has a key")
        // 8B is C major on the Camelot wheel; 5A is A minor, its relative — the two share all
        // three of these pitch classes, so either is a defensible reading of this signal.
        assertTrue(key == "8B" || key == "5A", "expected C major or its relative minor, got $key")
    }

    @Test
    fun findsALowPassWallAndIgnoresAFullSpectrum() {
        val bins = TrackAnalyzer.FRAME_SIZE / 2
        val binWidth = TrackAnalyzer.CUTOFF_SAMPLE_RATE / TrackAnalyzer.FRAME_SIZE

        // A spectrum that stops dead at 16kHz, as a lossy encoder leaves it.
        val walled = FloatArray(bins) { bin ->
            if (bin * binWidth < 16_000) 1.0f else 0.0f
        }
        val cutoff = assertNotNull(TrackAnalyzer.spectralCutoff(walled))
        assertTrue(abs(cutoff - 16_000) < binWidth * 2, "expected a wall near 16kHz, got $cutoff")

        // One that runs all the way up has no wall to report.
        assertNull(TrackAnalyzer.spectralCutoff(FloatArray(bins) { 1.0f }))
    }

    @Test
    fun fftMatchesAKnownSingleTone() {
        val size = TrackAnalyzer.FRAME_SIZE
        val rate = TrackAnalyzer.ANALYSIS_SAMPLE_RATE
        // Placed exactly on a bin centre, so the energy lands in one bin rather than smearing.
        val bin = 64
        val frequency = bin * rate / size
        val samples = FloatArray(size) { sin(2 * PI * frequency * it / rate).toFloat() }

        val magnitudes = FloatArray(size / 2)
        Fft.magnitudes(samples, FloatArray(size), FloatArray(size), magnitudes)

        val loudest = magnitudes.indices.maxByOrNull { magnitudes[it] }
        assertEquals(bin, loudest, "a tone on bin $bin should peak at bin $bin")
    }
}
