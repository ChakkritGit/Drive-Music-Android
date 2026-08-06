package com.drivemusic.android.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.drivemusic.shared.transition.TransitionPreset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the whole processor — configure, flush, queue, read — rather than the filter math alone.
 *
 * [BiquadTest] proves the coefficients are right; this proves they are *reached*. The bugs it is
 * aimed at are wiring bugs, which is where an audio chain actually goes wrong: a parameter that
 * never arrives, a gain applied before a filter instead of after, one channel's state feeding the
 * other, a sample that overflows instead of clipping. None of those show up in a coefficient test,
 * and all of them are audible.
 *
 * Still not a substitute for listening. This says the chain does what the curves ask; it says
 * nothing about whether what the curves ask sounds good.
 */
@UnstableApi
class TransitionAudioProcessorTest {
    private val sampleRate = 48_000
    private val chunkFrames = 512

    private fun configured(channelCount: Int = 1): TransitionAudioProcessor {
        val processor = TransitionAudioProcessor()
        processor.configure(
            AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_16BIT)
        )
        processor.flush()
        return processor
    }

    /** Pushes one chunk of interleaved 16-bit frames through and returns what came out. */
    private fun push(processor: TransitionAudioProcessor, samples: ShortArray): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()

        processor.queueInput(input)
        val output = processor.output
        val result = ShortArray(output.remaining() / 2)
        val shorts = output.order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in result.indices) result[i] = shorts.get()
        return result
    }

    private fun sineChunk(frequency: Double, startFrame: Int, frames: Int, channels: Int = 1) =
        ShortArray(frames * channels) { index ->
            val frame = startFrame + index / channels
            (sin(2 * PI * frequency * frame / sampleRate) * 0.8 * 32767).toInt().toShort()
        }

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        samples.forEach { sum += (it.toDouble() / 32768) * (it.toDouble() / 32768) }
        return sqrt(sum / samples.size)
    }

    /**
     * The end-to-end envelope check. A plain fade leaves every filter open, so the output level is
     * the volume lane and nothing else — which makes it the one preset whose envelope can be
     * predicted exactly and compared against.
     */
    @Test
    fun aFadeProducesExactlyTheVolumeLane() {
        val processor = configured()
        val shape = TransitionPreset.FADE.shape
        val steps = 40

        for (step in 0..steps) {
            val t = step.toDouble() / steps
            processor.parameters = SlotAutomation.outgoing(shape, t)
            val output = push(processor, sineChunk(1_000.0, step * chunkFrames, chunkFrames))

            val expected = shape.outgoingVolume.valueAt(t) * 0.8 / sqrt(2.0)
            // Generous early on: the chunk spans a slice of the ramp rather than a point, so its
            // RMS is the average across that slice, not the value at its start.
            assertTrue(
                abs(rms(output) - expected) < 0.03,
                "at t=$t expected ~$expected, measured ${rms(output)}"
            )
        }
    }

    /** A slot at zero volume must be actually silent, not merely quiet. */
    @Test
    fun anIncomingSlotIsSilentAtTheStartOfAMix() {
        val processor = configured()
        processor.parameters = SlotAutomation.incoming(TransitionPreset.MIX.shape, 0.0)
        val output = push(processor, sineChunk(1_000.0, 0, chunkFrames))

        assertTrue(output.isNotEmpty())
        assertTrue(output.all { it.toInt() == 0 }, "expected digital silence, got ${output.max()}")
    }

    /**
     * The bass swap reaching real audio. This is the wiring the Mix preset lives on: if `bassDb`
     * never made it to the shelf, every test in [BiquadTest] would still pass and every mix would
     * still be muddy.
     */
    @Test
    fun theBassShelfReachesTheAudio() {
        val flat = configured()
        flat.parameters = SlotParameters.open
        val cut = configured()
        cut.parameters = SlotParameters.open.copy(bassDb = -24.0)

        // Several chunks so the filters settle before anything is measured.
        var flatOut = ShortArray(0)
        var cutOut = ShortArray(0)
        for (step in 0 until 40) {
            flatOut = push(flat, sineChunk(50.0, step * chunkFrames, chunkFrames))
            cutOut = push(cut, sineChunk(50.0, step * chunkFrames, chunkFrames))
        }

        val deltaDb = 20 * log10(rms(cutOut) / rms(flatOut))
        assertEquals(-24.0, deltaDb, 2.0, "50Hz should be 24dB down with the shelf applied")
    }

    /** The same shelf must leave the midrange alone, or a swap turns into a duck. */
    @Test
    fun theBassShelfLeavesTheMidrangeAlone() {
        val flat = configured().apply { parameters = SlotParameters.open }
        val cut = configured().apply { parameters = SlotParameters.open.copy(bassDb = -24.0) }

        var flatOut = ShortArray(0)
        var cutOut = ShortArray(0)
        for (step in 0 until 40) {
            flatOut = push(flat, sineChunk(4_000.0, step * chunkFrames, chunkFrames))
            cutOut = push(cut, sineChunk(4_000.0, step * chunkFrames, chunkFrames))
        }

        val deltaDb = 20 * log10(rms(cutOut) / rms(flatOut))
        assertTrue(abs(deltaDb) < 1.0, "4kHz moved by ${deltaDb}dB under a bass shelf")
    }

    /**
     * Each channel needs its own filter state. Sharing one across a stereo pair feeds each channel
     * the other's history, which reads as a hollow, phasey smear rather than as an obvious fault —
     * so it is worth asserting rather than eyeballing.
     */
    @Test
    fun stereoChannelsDoNotBleedIntoEachOther() {
        val processor = configured(channelCount = 2)
        processor.parameters = SlotParameters.open.copy(lowPassHz = 2_000.0)

        // Left carries a tone; right is silent throughout.
        var output = ShortArray(0)
        for (step in 0 until 20) {
            val interleaved = ShortArray(chunkFrames * 2)
            for (frame in 0 until chunkFrames) {
                val n = step * chunkFrames + frame
                interleaved[frame * 2] =
                    (sin(2 * PI * 500.0 * n / sampleRate) * 0.8 * 32767).toInt().toShort()
                interleaved[frame * 2 + 1] = 0
            }
            output = push(processor, interleaved)
        }

        val right = ShortArray(output.size / 2) { output[it * 2 + 1] }
        assertTrue(right.all { it.toInt() == 0 }, "signal leaked into the silent channel")
    }

    /**
     * A shelf boost can push a sample past full scale. Letting a Short overflow turns a moment of
     * loudness into a burst of noise at the opposite polarity — far worse than the clipping it
     * replaces.
     */
    @Test
    fun samplesPastFullScaleClipRatherThanWrap() {
        val processor = configured()
        processor.parameters = SlotParameters.open.copy(bassDb = 18.0)

        var sawFullScale = false
        for (step in 0 until 40) {
            val output = push(processor, sineChunk(50.0, step * chunkFrames, chunkFrames))
            // A wrap shows up as a sign flip between neighbouring samples of a slow sine.
            for (i in 1 until output.size) {
                val previous = output[i - 1].toInt()
                val current = output[i].toInt()
                if (abs(previous) > 30_000 && abs(current) > 30_000 && previous * current < 0) {
                    error("sample wrapped: $previous -> $current")
                }
            }
            if (output.any { abs(it.toInt()) >= 32_760 }) sawFullScale = true
        }
        assertTrue(sawFullScale, "the boost should have driven the signal into the clip point")
    }

    /** Stale filter history across a seek rings on the discontinuity at the new position. */
    @Test
    fun flushClearsFilterState() {
        val processor = configured()
        processor.parameters = SlotParameters.open.copy(lowPassHz = 500.0)
        repeat(20) { step -> push(processor, sineChunk(50.0, step * chunkFrames, chunkFrames)) }

        processor.flush()
        val afterFlush = push(processor, ShortArray(chunkFrames))
        assertTrue(afterFlush.all { it.toInt() == 0 }, "silence after a flush still rang")
    }

    /** Float buffers must be declined rather than misread as shorts, which would be loud noise. */
    @Test
    fun floatInputIsDeclined() {
        val processor = TransitionAudioProcessor()
        try {
            processor.configure(
                AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_FLOAT)
            )
            error("expected the float format to be declined")
        } catch (expected: AudioProcessor.UnhandledAudioFormatException) {
            // Media3 arranges a conversion to 16-bit instead.
        }
    }
}
