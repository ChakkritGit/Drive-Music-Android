package com.drivemusic.android.audio

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drivemusic.shared.transition.TransitionPlan
import com.drivemusic.shared.transition.TransitionPreset
import com.drivemusic.shared.transition.TransitionSettings
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Runs the engine on a real device against real files.
 *
 * The JVM tests cover the DSP; what they cannot cover is the part that only exists at runtime —
 * whether an `ExoPlayer` built with a custom [androidx.media3.exoplayer.audio.DefaultAudioSink]
 * carrying a custom processor actually decodes, renders and plays. That wiring is the whole basis
 * of the Android audio plan, and if it were wrong every JVM test would still pass while nothing
 * made a sound.
 *
 * Generates its own WAV files rather than shipping fixtures: the assertions are about transport
 * and hand-off, not about content, and a test that carries its own inputs cannot go stale.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class CrossfadeEngineInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A mono 44.1kHz WAV of a steady tone, written to the app's cache. */
    private fun writeTone(name: String, frequency: Double, seconds: Double): File {
        val sampleRate = 44_100
        val frames = (sampleRate * seconds).toInt()
        val dataBytes = frames * 2
        val file = File(context.cacheDir, name)

        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
            fun le32(value: Int) = out.write(
                byteArrayOf(
                    (value and 0xFF).toByte(),
                    ((value shr 8) and 0xFF).toByte(),
                    ((value shr 16) and 0xFF).toByte(),
                    ((value shr 24) and 0xFF).toByte(),
                )
            )
            fun le16(value: Int) = out.write(
                byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
            )

            ascii("RIFF"); le32(36 + dataBytes); ascii("WAVE")
            ascii("fmt "); le32(16); le16(1); le16(1)
            le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
            ascii("data"); le32(dataBytes)

            val samples = ByteArray(dataBytes)
            for (frame in 0 until frames) {
                val value = (sin(2 * PI * frequency * frame / sampleRate) * 0.6 * 32767).toInt()
                samples[frame * 2] = (value and 0xFF).toByte()
                samples[frame * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            out.write(samples)
        }
        return file
    }

    /**
     * Every read of [Player.playbackState] hops to the main thread. ExoPlayer enforces
     * single-thread access and throws rather than tolerating it — which is the correct behavior,
     * and the reason polling it from the instrumentation thread fails on the first tick.
     */
    private suspend fun awaitState(player: Player, state: Int, timeoutMs: Long = 10_000) {
        withTimeout(timeoutMs) {
            while (withContextMain { player.playbackState } != state) delay(50)
        }
    }

    /**
     * The load path: a player built with the custom sink reaches READY on a real file. If the sink
     * were misconfigured this is where it would fail, and it would fail for every track.
     */
    @Test
    fun aPlayerWithTheCustomSinkReachesReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val engine = withContextMain { CrossfadeEngine(context, scope) }
        val tone = writeTone("ready.wav", 440.0, 3.0)

        try {
            withContextMain {
                engine.prepare(PlaybackSlot.A, tone.toURI().toString())
            }
            awaitState(engine.player(PlaybackSlot.A), Player.STATE_READY)
            assertEquals(
                Player.STATE_READY,
                withContextMain { engine.player(PlaybackSlot.A).playbackState },
            )
        } finally {
            withContextMain { engine.release() }
            scope.cancel()
        }
    }

    /** Audio actually renders: the playback position advances through the custom processor. */
    @Test
    fun playbackAdvancesThroughTheProcessor() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val engine = withContextMain { CrossfadeEngine(context, scope) }
        val tone = writeTone("advance.wav", 440.0, 5.0)

        try {
            withContextMain {
                engine.prepare(PlaybackSlot.A, tone.toURI().toString())
                engine.player(PlaybackSlot.A).playWhenReady = true
            }
            awaitState(engine.player(PlaybackSlot.A), Player.STATE_READY)
            delay(1_500)

            val position = withContextMain { engine.player(PlaybackSlot.A).currentPosition }
            assertTrue(position > 500, "position was ${position}ms after 1.5s of playback")
        } finally {
            withContextMain { engine.release() }
            scope.cancel()
        }
    }

    /**
     * The hand-off itself: a transition runs to completion and the other slot becomes active.
     *
     * Says nothing about how it sounds — that needs ears and the bench screen. It says the ramp
     * starts, finishes, and leaves the engine in the state the next transition depends on.
     */
    @Test
    fun aTransitionCompletesAndHandsOver() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val engine = withContextMain { CrossfadeEngine(context, scope) }
        val a = writeTone("mix-a.wav", 220.0, 10.0)
        val b = writeTone("mix-b.wav", 330.0, 10.0)

        try {
            withContextMain {
                engine.prepare(PlaybackSlot.A, a.toURI().toString())
                engine.player(PlaybackSlot.A).playWhenReady = true
            }
            awaitState(engine.player(PlaybackSlot.A), Player.STATE_READY)

            val plan = TransitionPlan.resolve(
                settings = TransitionSettings(shape = TransitionPreset.MIX.shape),
                outgoing = null,
                incoming = null,
                outgoingDuration = null,
                fallbackDuration = 2.0,
                autoMixEnabled = true,
                beatmatchEnabledByDefault = false,
            )

            withContextMain {
                engine.prepare(PlaybackSlot.B, b.toURI().toString())
                engine.startTransition(plan)
            }
            assertTrue(withContextMain { engine.isTransitioning })

            withTimeout(10_000) {
                while (withContextMain { engine.isTransitioning }) delay(50)
            }
            assertEquals(PlaybackSlot.B, withContextMain { engine.activeSlot })
        } finally {
            withContextMain { engine.release() }
            scope.cancel()
        }
    }

    /** ExoPlayer is single-threaded and must be touched only from the thread that built it. */
    private suspend fun <T> withContextMain(block: () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.Main) { block() }
}
