package com.drivemusic.android.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Decodes an audio file to mono float samples at a chosen rate.
 *
 * This is the one genuinely platform-specific part of track analysis — `AVAudioConverter` on iOS,
 * `MediaCodec` here. Everything downstream of it is shared Kotlin.
 *
 * Streams: samples are handed to the caller in blocks and never assembled here. Analysis at
 * 44.1kHz over a five-minute track is thirteen million floats, and the spectral cutoff pass only
 * ever looks at one frame at a time — an accumulating decoder would make it the largest allocation
 * in the app for no benefit. Callers that genuinely need the whole track ask for it explicitly.
 */
object AudioDecoder {

    /**
     * Decodes [file] to mono at [targetRate], calling [onBlock] with each converted block.
     *
     * The block is reused between calls; anything the caller keeps, it must copy.
     */
    fun forEachMonoBlock(
        file: File,
        targetRate: Double,
        onBlock: (samples: FloatArray, count: Int) -> Unit,
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return false

            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Nearest-neighbour decimation rather than a filtered resample. Everything this feeds
            // — onset flux, chroma, a peak envelope — is measuring energy and periodicity, and
            // aliasing above the analysis band shifts none of that enough to change a tempo or a
            // pitch class. A polyphase filter would be more correct and would cost more than the
            // FFT it feeds.
            val step = sourceRate / targetRate
            var sourcePosition = 0.0
            var sourceIndex = 0L

            val info = MediaCodec.BufferInfo()
            val block = FloatArray(BLOCK_SAMPLES)
            var blockCount = 0
            var sawInputEnd = false
            var sawOutputEnd = false

            while (!sawOutputEnd) {
                if (!sawInputEnd) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val frames = info.size / 2 / channels
                        for (frame in 0 until frames) {
                            // Averaged to mono rather than taking one channel: a track with its
                            // percussion panned hard would otherwise lose exactly the transients
                            // the onset detector is looking for.
                            var sum = 0f
                            for (channel in 0 until channels) {
                                sum += shorts.get(frame * channels + channel) / SHORT_SCALE
                            }
                            val sample = sum / channels

                            if (sourceIndex.toDouble() >= sourcePosition) {
                                block[blockCount++] = sample
                                sourcePosition += step
                                if (blockCount == block.size) {
                                    onBlock(block, blockCount)
                                    blockCount = 0
                                }
                            }
                            sourceIndex++
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEnd = true
                    }
                }
            }

            if (blockCount > 0) onBlock(block, blockCount)
            return true
        } catch (error: Exception) {
            // A file that will not decode is not an error worth surfacing: analysis is an
            // enhancement, and the player falls back to a plain crossfade without it.
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * The whole file as mono floats at [targetRate], capped at [maxSeconds].
     *
     * The cap is what keeps a two-hour DJ set from becoming a hundred-megabyte array: tempo, key
     * and the loudness shape of the opening are all decided long before then, and anything longer
     * than the cap is not a track this feature is for.
     */
    fun monoSamples(file: File, targetRate: Double, maxSeconds: Double = MAX_ANALYSIS_SECONDS): FloatArray {
        val limit = (targetRate * maxSeconds).toInt()
        var output = FloatArray(min(limit, INITIAL_CAPACITY))
        var count = 0
        forEachMonoBlock(file, targetRate) { block, blockCount ->
            if (count >= limit) return@forEachMonoBlock
            val take = min(blockCount, limit - count)
            if (count + take > output.size) {
                output = output.copyOf(min(limit, max2(output.size * 2, count + take)))
            }
            block.copyInto(output, count, 0, take)
            count += take
        }
        return output.copyOf(count)
    }

    private fun max2(a: Int, b: Int) = if (a > b) a else b

    private const val SHORT_SCALE = 32768f
    private const val BLOCK_SAMPLES = 8192
    private const val TIMEOUT_US = 10_000L
    private const val INITIAL_CAPACITY = 1 shl 20
    private const val MAX_ANALYSIS_SECONDS = 12 * 60.0
}
