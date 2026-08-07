package com.drivemusic.shared.model

import com.drivemusic.shared.playback.PlaybackQueueState
import kotlinx.serialization.Serializable

/**
 * A snapshot of "what was playing and how", saved continuously so relaunching restores the last
 * track, its queue and source, and the playback settings — rather than losing them.
 */
@Serializable
data class PlaybackSession(
    val queue: List<DriveFile>,
    val currentIndex: Int,
    val source: PlaySource? = null,
    val progress: Double = 0.0,
    val shuffle: Boolean = false,
    /**
     * The live shuffle window, persisted so a relaunch does not re-roll a fresh random order for
     * a session that was already under way.
     */
    val shuffleOrder: List<Int> = emptyList(),
    val loopMode: LoopMode = LoopMode.OFF,
    val volume: Double = 1.0,
) {
    /**
     * The queue half of this session, ready to resume.
     *
     * `playNextIndex` is deliberately not persisted and comes back null: it means "the track the
     * user just asked to hear next", which is an intent about the current listening moment, not
     * about the queue. Restoring it days later would jump somewhere the user no longer remembers
     * asking for.
     */
    fun toQueueState(): PlaybackQueueState = PlaybackQueueState(
        tracks = queue,
        currentIndex = currentIndex.takeIf { queue.indices.contains(it) },
        shuffle = shuffle,
        shuffleOrder = shuffleOrder,
        loopMode = loopMode,
        playNextIndex = null,
    )

    companion object {
        fun from(
            state: PlaybackQueueState,
            source: PlaySource?,
            progress: Double,
            volume: Double,
        ): PlaybackSession? {
            val index = state.currentIndex ?: return null
            if (state.tracks.isEmpty()) return null
            return PlaybackSession(
                queue = state.tracks,
                currentIndex = index,
                source = source,
                progress = progress,
                shuffle = state.shuffle,
                shuffleOrder = state.shuffleOrder,
                loopMode = state.loopMode,
                volume = volume,
            )
        }
    }
}
