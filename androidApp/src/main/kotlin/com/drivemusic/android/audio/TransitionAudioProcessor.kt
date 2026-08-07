package com.drivemusic.android.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * One slot's filter chain, inserted into that player's own audio pipeline.
 *
 * This is the answer to "Android has no `AVAudioEngine`". Media3 lets each player carry its own
 * chain of [AudioProcessor]s, which is exactly the per-slot hook the transition needs — the
 * session-wide `audiofx` effects cannot express it, because a transition's whole substance is
 * doing different things to two tracks at the same moment.
 *
 * Order matters and mirrors the iOS node graph: high-pass, low-pass, bass shelf, reverb, then
 * gain. Filtering before gain means the ramp is applied to already-filtered audio, so a lane that
 * closes a filter to nothing does not also have to fight the volume lane. Reverb sits after the
 * filters and before gain so the tail is built from what the listener is actually hearing — a
 * track being filtered down washes out into a reverb of its filtered self, which is the point of
 * the effect — and so the volume lane fades the wet tail along with everything else rather than
 * leaving it hanging at full level after the track has gone.
 *
 * [parameters] is written from the playback thread and read on the audio thread every buffer. It
 * is a single immutable value for the same reason [Biquad.coefficients] is: reading a
 * half-updated parameter set produces a filter that is briefly nonsense, and "briefly nonsense"
 * in a feedback path can mean a sustained blast.
 */
@UnstableApi
class TransitionAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var parameters: SlotParameters = SlotParameters.open
        set(value) {
            field = value
            updateCoefficients(value)
        }

    /**
     * How loud the last buffer was, 0..1, smoothed — read by the Now Playing glow so it can move
     * with the music.
     *
     * Computed here rather than from a separate `Visualizer`: this processor already sees every
     * sample on its way to the sink, so the reading costs one multiply-accumulate per frame and
     * needs no extra permission (`Visualizer` requires RECORD_AUDIO, which is a microphone
     * prompt for a decoration).
     *
     * `@Volatile` because the audio thread writes it and the UI thread reads it, and neither
     * should ever wait on the other for it — a glow one frame stale is not a defect.
     */
    @Volatile
    var level: Float = 0f
        private set

    private var channelCount = 0
    private var sampleRate = 0

    // One filter per channel — biquad state is per-signal, and sharing one across a stereo pair
    // would feed each channel the other's history, which reads as a hollow, phasey smear.
    private var highPass: Array<Biquad> = emptyArray()
    private var lowPass: Array<Biquad> = emptyArray()
    private var bass: Array<Biquad> = emptyArray()
    private var reverb: Array<Reverb> = emptyArray()
    // The user's tone controls, applied after the transition's own filtering so a bass swap can
    // never cancel a standing preference.
    private var eqBass: Array<Biquad> = emptyArray()
    private var eqMid: Array<Biquad> = emptyArray()
    private var eqTreble: Array<Biquad> = emptyArray()

    /**
     * The user's spatial reverb, distinct from [reverb] above — that one belongs to the transition
     * and is driven by it. Two separate reverbs rather than one summed wetness: a mix that swells
     * its own reverb must not also undo whatever the user has set, and one shared tail could not
     * tell the two apart.
     */
    private var spatial: Array<Reverb> = emptyArray()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // 16-bit PCM only. Media3 hands float buffers through when float output is enabled, and
        // silently misreading those as shorts would be loud noise rather than a quiet bug, so this
        // declines the format instead and Media3 arranges a conversion.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        highPass = Array(channelCount) { Biquad() }
        lowPass = Array(channelCount) { Biquad() }
        bass = Array(channelCount) { Biquad() }
        reverb = Array(channelCount) { Reverb(inputAudioFormat.sampleRate.toDouble()) }
        spatial = Array(channelCount) { Reverb(inputAudioFormat.sampleRate.toDouble()) }
        eqBass = Array(channelCount) { Biquad() }
        eqMid = Array(channelCount) { Biquad() }
        eqTreble = Array(channelCount) { Biquad() }
        updateCoefficients(parameters)
        return inputAudioFormat
    }

    /**
     * Always active. Returning false when the chain happens to be flat would let Media3 drop this
     * processor out of the pipeline entirely, and then a transition starting a moment later would
     * have nowhere to apply itself.
     */
    override fun isActive(): Boolean = channelCount > 0

    private fun updateCoefficients(value: SlotParameters) {
        if (sampleRate <= 0) return
        val rate = sampleRate.toDouble()
        val hp = Biquad.highPass(value.highPassHz, rate)
        val lp = Biquad.lowPass(value.lowPassHz, rate)
        // 250Hz corner: high enough to take the weight out of a track, low enough to leave the
        // vocal and snare body alone, which is what makes a swap sound like a handover instead of
        // a filter sweep.
        val shelf = Biquad.lowShelf(BASS_SHELF_HZ, value.bassDb, rate)

        val eq = value.eq
        val toneBass = if (eq.isFlat) Biquad.Coefficients.bypass
            else Biquad.lowShelf(EQ_BASS_HZ, eq.bassDb, rate)
        val toneMid = if (eq.isFlat) Biquad.Coefficients.bypass
            else Biquad.peaking(EQ_MID_HZ, eq.midDb, rate)
        val toneTreble = if (eq.isFlat) Biquad.Coefficients.bypass
            else Biquad.highShelf(EQ_TREBLE_HZ, eq.trebleDb, rate)

        for (channel in 0 until channelCount) {
            highPass[channel].coefficients = hp
            lowPass[channel].coefficients = lp
            bass[channel].coefficients = shelf
            eqBass[channel].coefficients = toneBass
            eqMid[channel].coefficients = toneMid
            eqTreble[channel].coefficients = toneTreble
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (2 * channelCount)
        val output = replaceOutputBuffer(frames * 2 * channelCount)
        val input = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        // Read once per buffer, not per sample: `parameters` is written from another thread, and
        // a value that changed mid-buffer would apply half a ramp step to half the samples.
        val current = parameters
        val volume = current.volume
        val wet = (current.reverbWet / 100).coerceIn(0.0, 1.0)
        // Halved, matching the iOS ceiling: at full intensity the track should sound like it is in
        // a large room, never like it has been replaced by its own reflections.
        val spatialWet = (current.spatialWet / 100 * SPATIAL_WET_CEILING).coerceIn(0.0, 1.0)
        val toneEnabled = !current.eq.isFlat
        var peak = 0.0

        for (frame in 0 until frames) {
            for (channel in 0 until channelCount) {
                var sample = input.get().toDouble() / SHORT_SCALE
                sample = highPass[channel].process(sample)
                sample = lowPass[channel].process(sample)
                sample = bass[channel].process(sample)
                if (wet > 0) {
                    // Mixed against the dry signal rather than replacing it, and the dry side is
                    // only partly pulled back: at full wet the track should sound like it is in a
                    // large room, not like it has been replaced by its own echo.
                    val tail = reverb[channel].process(sample.toFloat()).toDouble()
                    sample = sample * (1 - wet * 0.5) + tail * wet
                }
                if (toneEnabled) {
                    sample = eqBass[channel].process(sample)
                    sample = eqMid[channel].process(sample)
                    sample = eqTreble[channel].process(sample)
                }
                if (spatialWet > 0) {
                    // After the tone controls, as on iOS, where this sits downstream of the user
                    // EQ in the graph: the space is applied to the sound the user has shaped, not
                    // shaped along with it.
                    val tail = spatial[channel].process(sample.toFloat()).toDouble()
                    sample = sample * (1 - spatialWet * 0.5) + tail * spatialWet
                }
                sample *= volume
                // Clipped, not wrapped. A shelf boost or a resonant corner can push a sample past
                // full scale, and letting a Short overflow turns a moment of loudness into a burst
                // of noise at the opposite polarity.
                val scaled = (sample * SHORT_SCALE).roundToInt().coerceIn(MIN_SHORT, MAX_SHORT)
                val magnitude = if (scaled < 0) -scaled.toDouble() else scaled.toDouble()
                if (magnitude > peak) peak = magnitude
                output.putShort(scaled.toShort())
            }
        }
        // Peak of the buffer, then smoothed towards it. RMS is the more correct measure of
        // loudness but it barely moves on dense modern masters, so the glow would sit at a
        // constant size; peak follows the transients, which is what "reacts to the music" means
        // to someone watching it. Attack is fast and release slow, or every gap between beats
        // reads as the glow collapsing.
        val target = (peak / SHORT_SCALE).toFloat().coerceIn(0f, 1f)
        val smoothing = if (target > level) LEVEL_ATTACK else LEVEL_RELEASE
        level += (target - level) * smoothing

        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        resetFilterState()
        // A level left over from before a seek would hold the glow at the old position's loudness
        // until the next buffer arrives.
        level = 0f
    }

    override fun onReset() {
        resetFilterState()
        channelCount = 0
        sampleRate = 0
        reverb = emptyArray()
        spatial = emptyArray()
    }

    private fun resetFilterState() {
        // Stale history across a seek is a transient at the new position — the filters are fed a
        // discontinuity and ring on it.
        highPass.forEach { it.reset() }
        lowPass.forEach { it.reset() }
        bass.forEach { it.reset() }
        // A reverb tail that survives a seek is the previous position still audibly ringing.
        reverb.forEach { it.clear() }
        spatial.forEach { it.clear() }
        eqBass.forEach { it.reset() }
        eqMid.forEach { it.reset() }
        eqTreble.forEach { it.reset() }
    }

    companion object {
        const val BASS_SHELF_HZ = 250.0
        const val EQ_BASS_HZ = 100.0
        const val EQ_MID_HZ = 1_000.0
        const val EQ_TREBLE_HZ = 6_000.0
        private const val SHORT_SCALE = 32768.0
        private const val MIN_SHORT = -32768
        private const val MAX_SHORT = 32767

        /**
         * Never fully wet. Matches the 0.5 ceiling `applySpatialAudio` uses on iOS, which the web
         * version calls `WET_MAX` — some of the original signal always stays audible.
         */
        const val SPATIAL_WET_CEILING = 0.5

        /** Fast up, slow down — see `level`. */
        private const val LEVEL_ATTACK = 0.5f
        private const val LEVEL_RELEASE = 0.08f
    }
}
