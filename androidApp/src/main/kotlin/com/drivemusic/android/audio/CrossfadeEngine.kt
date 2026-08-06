package com.drivemusic.android.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.drivemusic.shared.transition.TransitionPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Which of the two players is which. */
enum class PlaybackSlot {
    A, B;

    val other: PlaybackSlot get() = if (this == A) B else A
}

/**
 * Two players, each with its own filter chain, and a ramp that drives both from a shared
 * [com.drivemusic.shared.transition.TransitionShape].
 *
 * This is the Android answer to the iOS `PlaybackGraph` + `CrossfadeController` pair. The
 * structure is deliberately the same — two slots, one active, transitions hand over between them —
 * because the orchestration logic above it is meant to be shared eventually, and an engine with a
 * different shape would make that impossible.
 *
 * **This is a spike.** It establishes that per-slot filtering and a curve-driven crossfade are
 * expressible on Media3, and it has not been listened to. See the notes on [startTransition] for
 * what is still missing before it is a player.
 */
@UnstableApi
class CrossfadeEngine(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val processors = mapOf(
        PlaybackSlot.A to TransitionAudioProcessor(),
        PlaybackSlot.B to TransitionAudioProcessor(),
    )

    private val players: Map<PlaybackSlot, ExoPlayer> = PlaybackSlot.entries.associateWith { slot ->
        buildPlayer(context, processors.getValue(slot))
    }

    var activeSlot: PlaybackSlot = PlaybackSlot.A
        private set

    var isTransitioning: Boolean = false
        private set

    private var rampJob: Job? = null

    fun player(slot: PlaybackSlot): ExoPlayer = players.getValue(slot)

    /**
     * A player whose audio sink carries [processor].
     *
     * Each player gets its own sink and its own processor instance — that separation is the whole
     * reason this works at all. `android.media.audiofx` effects attach to an audio session shared
     * by everything the app plays, so they cannot filter one track and not the other.
     */
    private fun buildPlayer(context: Context, processor: TransitionAudioProcessor): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(processor))
                // Float output would hand the processor float buffers, which it declines — see
                // `onConfigure`. Keeping it off avoids a needless conversion each way.
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
        return ExoPlayer.Builder(context, renderersFactory).build()
    }

    fun prepare(slot: PlaybackSlot, uri: String) {
        val player = player(slot)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    /**
     * Runs [plan]'s curves across both slots, then makes the incoming one active.
     *
     * The ramp is a coroutine ticking at [RAMP_INTERVAL_MS] rather than anything sample-accurate.
     * That is the same trade the iOS side makes and for the same reason: the curves are read ~30
     * times a second, and every lane interpolates linearly between keyframes, so a tick's worth of
     * quantisation is inaudible on volume and filter sweeps.
     *
     * Still missing before this is a player, all of it deliberate for a spike:
     * - the beatmatch stretch is applied as a plain [PlaybackParameters] speed change, which
     *   moves pitch with it; Media3's Sonic can hold pitch, and should
     * - reverb is not applied at all (see [SlotAutomation.reverbWet])
     * - `plan.outgoingLoop` is ignored, so the Rise preset's held bar does not happen
     * - nothing here is verified by ear, which for an audio path is the only verification that
     *   ultimately counts
     */
    fun startTransition(plan: TransitionPlan, onComplete: (PlaybackSlot) -> Unit = {}) {
        if (isTransitioning) return
        val outgoing = activeSlot
        val incoming = outgoing.other

        isTransitioning = true
        rampJob?.cancel()

        // Positioned and started before the ramp so the first tick lands on audio that is already
        // moving; starting it inside the loop would leave a tick of silence at t=0.
        player(incoming).apply {
            seekTo((plan.incomingStartSeconds * 1000).toLong())
            playbackParameters = PlaybackParameters(plan.incomingRate)
            playWhenReady = true
        }
        processors.getValue(incoming).parameters = SlotAutomation.incoming(plan.shape, 0.0)
        processors.getValue(outgoing).parameters = SlotAutomation.outgoing(plan.shape, 0.0)

        rampJob = scope.launch {
            val durationMs = (plan.duration * 1000).toLong().coerceAtLeast(1)
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startedAt
                val t = (elapsed.toDouble() / durationMs).coerceIn(0.0, 1.0)
                processors.getValue(outgoing).parameters = SlotAutomation.outgoing(plan.shape, t)
                processors.getValue(incoming).parameters = SlotAutomation.incoming(plan.shape, t)
                if (t >= 1.0) break
                delay(RAMP_INTERVAL_MS)
            }

            player(outgoing).apply {
                stop()
                playbackParameters = PlaybackParameters(1f)
            }
            processors.getValue(outgoing).parameters = SlotParameters.silent
            // The incoming slot is now simply playing, so its chain has to be open — leaving it at
            // the transition's end state would keep whatever filtering the last keyframe held for
            // the rest of the track.
            processors.getValue(incoming).parameters = SlotParameters.open
            player(incoming).playbackParameters = PlaybackParameters(1f)

            activeSlot = incoming
            isTransitioning = false
            onComplete(incoming)
        }
    }

    /**
     * Abandons a transition in progress, leaving the slot that was active still active and at full
     * level. Mirrors the iOS `cancelCrossfade` — and note what that one got wrong for a long time:
     * restoring to a hard-coded full volume discards the track's normalization gain. Here the
     * caller owns per-track gain outside the processor entirely, so restoring the chain to `open`
     * is only about the transition's own automation.
     */
    fun cancelTransition() {
        if (!isTransitioning) return
        rampJob?.cancel()
        rampJob = null
        isTransitioning = false

        processors.getValue(activeSlot).parameters = SlotParameters.open
        processors.getValue(activeSlot.other).parameters = SlotParameters.silent
        player(activeSlot.other).apply {
            stop()
            playbackParameters = PlaybackParameters(1f)
        }
    }

    fun release() {
        rampJob?.cancel()
        players.values.forEach { it.release() }
    }

    companion object {
        /** ~30Hz, matching the iOS ramp. */
        const val RAMP_INTERVAL_MS = 33L
    }
}
