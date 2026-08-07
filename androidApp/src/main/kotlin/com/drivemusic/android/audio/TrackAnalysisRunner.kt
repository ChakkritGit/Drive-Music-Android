package com.drivemusic.android.audio

import com.drivemusic.shared.analysis.Fft
import com.drivemusic.shared.analysis.MixPoints
import com.drivemusic.shared.analysis.TrackAnalyzer
import com.drivemusic.shared.model.TrackAnalysis
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the shared analyser over a decoded file and assembles a [TrackAnalysis].
 *
 * Mirrors `TrackAnalyzer.analyze(url:fileId:)`, including the ordering: everything that shares the
 * reduced-rate decode is computed while those samples are in hand, and the spectral cutoff — which
 * needs its own decode at twice the rate — runs afterwards, so the two largest arrays in the app
 * are never alive at the same time.
 */
object TrackAnalysisRunner {

    suspend fun analyze(file: File, fileId: String): TrackAnalysis? = withContext(Dispatchers.Default) {
        val samples = AudioDecoder.monoSamples(file, TrackAnalyzer.ANALYSIS_SAMPLE_RATE)
        if (samples.isEmpty()) return@withContext null

        val duration = samples.size / TrackAnalyzer.ANALYSIS_SAMPLE_RATE
        val waveform = TrackAnalyzer.envelope(samples)
        val tempo = TrackAnalyzer.tempoAndPhase(TrackAnalyzer.spectralFlux(samples))
        val key = TrackAnalyzer.detectKey(samples)
        val gain = TrackAnalyzer.loudnessGain(samples)

        val mixIn = MixPoints.mixInPoint(
            envelope = waveform,
            duration = duration,
            bpm = tempo.bpm,
            firstBeat = tempo.firstBeatSeconds,
        )
        val mixOut = MixPoints.mixOutPoint(envelope = waveform, duration = duration)

        // Last, and from its own decode, so a failure here costs nothing already computed.
        val cutoff = TrackAnalyzer.spectralCutoff(averageSpectrum(file) ?: FloatArray(0))

        TrackAnalysis(
            fileId = fileId,
            bpm = tempo.bpm,
            firstBeatSeconds = tempo.firstBeatSeconds,
            camelotKey = key,
            mixInSeconds = mixIn,
            mixOutSeconds = mixOut,
            durationSeconds = duration,
            spectralCutoffHz = cutoff,
            loudnessGain = gain,
            waveform = waveform,
            version = TrackAnalyzer.VERSION,
        )
    }

    /**
     * The magnitude spectrum averaged over the whole file at the cutoff rate.
     *
     * Accumulated one frame at a time straight from the decoder, so the file is never held as an
     * array. This is the pass that made the equivalent iOS code the app's largest allocation
     * before it was rewritten the same way.
     */
    private fun averageSpectrum(file: File): FloatArray? {
        val halfSize = TrackAnalyzer.FRAME_SIZE / 2
        val window = Fft.hannWindow(TrackAnalyzer.FRAME_SIZE)
        val frame = FloatArray(TrackAnalyzer.FRAME_SIZE)
        val windowed = FloatArray(TrackAnalyzer.FRAME_SIZE)
        val real = FloatArray(TrackAnalyzer.FRAME_SIZE)
        val imaginary = FloatArray(TrackAnalyzer.FRAME_SIZE)
        val magnitudes = FloatArray(halfSize)
        val total = DoubleArray(halfSize)

        var filled = 0
        var frames = 0

        val decoded = AudioDecoder.forEachMonoBlock(file, TrackAnalyzer.CUTOFF_SAMPLE_RATE) { block, count ->
            var offset = 0
            while (offset < count) {
                val take = minOf(TrackAnalyzer.FRAME_SIZE - filled, count - offset)
                block.copyInto(frame, filled, offset, offset + take)
                filled += take
                offset += take
                if (filled == TrackAnalyzer.FRAME_SIZE) {
                    for (i in 0 until TrackAnalyzer.FRAME_SIZE) windowed[i] = frame[i] * window[i]
                    Fft.magnitudes(windowed, real, imaginary, magnitudes)
                    for (bin in 0 until halfSize) total[bin] += magnitudes[bin].toDouble()
                    frames++
                    // Hop by the frame, not by `hopSize`: this is a long-term average, and
                    // overlapping frames would quadruple the work to average in more copies of
                    // the same thing.
                    filled = 0
                }
            }
        }

        if (!decoded || frames == 0) return null
        return FloatArray(halfSize) { (total[it] / frames).toFloat() }
    }
}
