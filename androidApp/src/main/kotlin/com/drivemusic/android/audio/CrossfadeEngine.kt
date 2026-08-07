package com.drivemusic.android.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val mediaSourceFactory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
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
     * The beatmatch stretch is a [PlaybackParameters] speed change, and that already holds pitch:
     * `PlaybackParameters` carries speed and pitch as separate values, and Media3 routes speed
     * through `SonicAudioProcessor`, which time-stretches. Worth stating because it is easy to
     * assume otherwise — and because `setAudioProcessors` replaces the sink's processor list, so
     * the question of whether Sonic survives a custom chain is a real one. It does:
     * `DefaultAudioProcessorChain` appends its own Sonic after whatever is passed in. Pinned by
     * `CrossfadeEngineInstrumentedTest.aTempoStretchChangesTheRate`.
     *
     * `plan.outgoingLoop` holds the outgoing track's last bar or two under the transition — how a
     * DJ stretches a phrase to buy time. See [armOutgoingLoop] for why it is set up before the
     * ramp rather than driven from it.
     */
    fun startTransition(plan: TransitionPlan, onComplete: (PlaybackSlot) -> Unit = {}) {
        if (isTransitioning) return
        val outgoing = activeSlot
        val incoming = outgoing.other

        isTransitioning = true
        rampJob?.cancel()

        plan.outgoingLoop?.let { armOutgoingLoop(outgoing, it) }

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
                // Cleared or the slot would still be a looping clip the next time it is used.
                repeatMode = Player.REPEAT_MODE_OFF
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
     * Replaces the outgoing slot's source with just the looped stretch, repeating.
     *
     * A [ClippingMediaSource] with `REPEAT_MODE_ONE` rather than watching the position and seeking
     * back: the ramp ticks at [RAMP_INTERVAL_MS], so a seek-driven loop would overshoot its end by
     * up to a tick and stutter on every pass. Clipping makes the loop a property of the source, so
     * the seam is handled inside the player at frame accuracy.
     *
     * The cost is that this re-prepares the outgoing player mid-transition, which is a brief
     * discontinuity in a track that is already being filtered and faded — acceptable there, and
     * the reason a loop is only ever used by presets that ask for one.
     */
    private fun armOutgoingLoop(slot: PlaybackSlot, loop: ClosedFloatingPointRange<Double>) {
        val player = player(slot)
        val item = player.currentMediaItem ?: return
        val clipped = ClippingMediaSource.Builder(mediaSourceFactory.createMediaSource(item))
            .setStartPositionMs((loop.start * 1000).toLong())
            .setEndPositionMs((loop.endInclusive * 1000).toLong())
            .build()

        player.setMediaSource(clipped)
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.prepare()
        player.playWhenReady = true
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
        // The slot that survives may have been turned into a looping clip by `armOutgoingLoop`,
        // and a cancelled transition has to leave it playing the whole track again.
        player(activeSlot).apply {
            if (repeatMode != Player.REPEAT_MODE_OFF) {
                repeatMode = Player.REPEAT_MODE_OFF
                currentMediaItem?.let { item ->
                    val position = currentPosition
                    setMediaItem(item)
                    prepare()
                    seekTo(position)
                    playWhenReady = true
                }
            }
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
