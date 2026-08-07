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
        val toneEnabled = !current.eq.isFlat

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
                sample *= volume
                // Clipped, not wrapped. A shelf boost or a resonant corner can push a sample past
                // full scale, and letting a Short overflow turns a moment of loudness into a burst
                // of noise at the opposite polarity.
                val scaled = (sample * SHORT_SCALE).roundToInt().coerceIn(MIN_SHORT, MAX_SHORT)
                output.putShort(scaled.toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        resetFilterState()
    }

    override fun onReset() {
        resetFilterState()
        channelCount = 0
        sampleRate = 0
        reverb = emptyArray()
    }

    private fun resetFilterState() {
        // Stale history across a seek is a transient at the new position — the filters are fed a
        // discontinuity and ring on it.
        highPass.forEach { it.reset() }
        lowPass.forEach { it.reset() }
        bass.forEach { it.reset() }
        // A reverb tail that survives a seek is the previous position still audibly ringing.
        reverb.forEach { it.clear() }
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
    }
}
