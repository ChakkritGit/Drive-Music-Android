package com.drivemusic.shared.playback

import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.LoopMode
import kotlin.random.Random

/**
 * Everything about "what is playing and what plays next", as a value.
 *
 * The iOS app keeps this as mutable properties on its player view model, tangled with the audio
 * engine, the download cache and the now-playing surface — and almost every playback bug found in
 * that codebase lived here rather than in the audio: a queued track that never played, an Up Next
 * list that re-randomised itself, a shuffle window that topped up by one instead of twenty. None
 * of those need an audio engine to reproduce, and none of them were caught, because there was
 * nowhere to test them without one.
 *
 * So on this side the queue is a pure state machine. Every operation takes a state and returns a
 * new one, the platform layer watches [currentIndex] and loads what it points at, and every rule
 * below is covered by a test that runs on both platforms.
 */
data class PlaybackQueueState(
    val tracks: List<DriveFile> = emptyList(),
    val currentIndex: Int? = null,
    val shuffle: Boolean = false,
    /** The live shuffle window — see [Shuffle]. Empty whenever [shuffle] is off. */
    val shuffleOrder: List<Int> = emptyList(),
    val loopMode: LoopMode = LoopMode.OFF,
    /**
     * A queue position guaranteed to play next, overriding shuffle and sequential order for one
     * step. Set by [PlaybackQueue.addToQueue].
     */
    val playNextIndex: Int? = null,
) {
    val currentTrack: DriveFile?
        get() = currentIndex?.let { tracks.getOrNull(it) }

    val isEmpty: Boolean get() = tracks.isEmpty()
}

object PlaybackQueue {

    /** Starts a new queue at [startIndex], seeding a shuffle window if shuffle is on. */
    fun play(
        state: PlaybackQueueState,
        tracks: List<DriveFile>,
        startIndex: Int,
        weights: List<Double>,
        random: Random = Random.Default,
    ): PlaybackQueueState {
        val index = startIndex.coerceIn(0, maxOf(0, tracks.size - 1)).takeIf { tracks.isNotEmpty() }
        return state.copy(
            tracks = tracks,
            currentIndex = index,
            playNextIndex = null,
            shuffleOrder = if (state.shuffle && index != null) {
                Shuffle.seedWindow(tracks.size, index, weights, random)
            } else {
                emptyList()
            },
        )
    }

    /**
     * Turns shuffle on or off, seeding a window around the current track when turning it on.
     *
     * Picking a specific track from a list turns shuffle off — that is the caller's job, not this
     * one's, but it is worth knowing here: tapping a row is a request to hear the list *from
     * there*, and leaving shuffle on made the tapped track play and then jump somewhere random,
     * which reads as the tap having chosen the wrong thing.
     */
    fun setShuffle(
        state: PlaybackQueueState,
        enabled: Boolean,
        weights: List<Double>,
        random: Random = Random.Default,
    ): PlaybackQueueState {
        if (enabled == state.shuffle) return state
        val current = state.currentIndex
        return state.copy(
            shuffle = enabled,
            shuffleOrder = if (enabled && current != null) {
                Shuffle.seedWindow(state.tracks.size, current, weights, random)
            } else {
                emptyList()
            },
        )
    }

    /**
     * Makes [file] play immediately after the current track, moving it there if it is already
     * queued elsewhere rather than duplicating it.
     *
     * Two things this gets right that are easy to get wrong, both of which were real bugs:
     *
     * The shuffle order is re-indexed through the splice rather than cleared. Clearing it makes
     * the next skip seed a completely fresh random window, which the user sees as "adding one song
     * re-shuffled everything".
     *
     * The inserted position also goes into the shuffle order directly behind the current track, so
     * it still plays next once shuffle rather than [PlaybackQueueState.playNextIndex] is doing the
     * choosing.
     */
    fun addToQueue(state: PlaybackQueueState, file: DriveFile): PlaybackQueueState {
        // Already the current track — there is nothing to queue.
        if (state.currentTrack?.id == file.id) return state

        if (state.isEmpty) {
            return state.copy(
                tracks = listOf(file),
                currentIndex = 0,
                playNextIndex = null,
                shuffleOrder = emptyList(),
            )
        }

        val existingIndex = state.tracks.indexOfFirst { it.id == file.id }.takeIf { it >= 0 }
        val withoutExisting =
            if (existingIndex != null) state.tracks.filterIndexed { i, _ -> i != existingIndex }
            else state.tracks

        val adjustedCurrent = when {
            existingIndex != null && state.currentIndex != null && existingIndex < state.currentIndex ->
                state.currentIndex - 1
            else -> state.currentIndex ?: 0
        }

        val insertAt = adjustedCurrent + 1
        val tracks = withoutExisting.toMutableList().apply { add(insertAt, file) }

        return state.copy(
            tracks = tracks,
            currentIndex = adjustedCurrent,
            playNextIndex = insertAt,
            shuffleOrder = Shuffle.reindexForInsertion(
                state.shuffleOrder,
                removed = existingIndex,
                insertedAt = insertAt,
                currentIndex = adjustedCurrent,
            ),
        )
    }

    /**
     * Removes the track at [index]. A no-op on the currently playing track — removing what is
     * actively playing is a skip, not a queue edit.
     *
     * The shuffle order is adjusted in place rather than reset, same reasoning as [addToQueue]:
     * clearing it read as "removing one track from Up Next re-shuffled the whole list".
     */
    fun removeFromQueue(state: PlaybackQueueState, index: Int): PlaybackQueueState {
        if (index !in state.tracks.indices || index == state.currentIndex) return state

        val tracks = state.tracks.filterIndexed { i, _ -> i != index }
        val currentIndex = state.currentIndex?.let { if (index < it) it - 1 else it }
        val playNextIndex = state.playNextIndex?.let {
            when {
                it == index -> null
                it > index -> it - 1
                else -> it
            }
        }
        val shuffleOrder = state.shuffleOrder
            .filter { it != index }
            .map { if (it > index) it - 1 else it }

        return state.copy(
            tracks = tracks,
            currentIndex = currentIndex,
            playNextIndex = playNextIndex,
            shuffleOrder = shuffleOrder,
        )
    }

    /**
     * What will actually play next, without advancing.
     *
     * Reads [PlaybackQueueState.playNextIndex] first, exactly as [next] does. On iOS it did not,
     * and the two paths that hand over *without* going through `next` — an armed crossfade and an
     * armed gapless hand-off — therefore picked the sequential or shuffled successor instead. A
     * track queued with "play next" was skipped entirely whenever the current one was allowed to
     * run to its end; it only ever played if the user hit next by hand.
     *
     * Returns null when there is genuinely nothing coming up: loop-one repeats rather than
     * advances, and a queue at its end with looping off has nothing after it.
     */
    fun peekNextIndex(state: PlaybackQueueState): Int? {
        if (state.loopMode == LoopMode.ONE) return null

        val queued = state.playNextIndex
        if (queued != null && queued in state.tracks.indices && queued != state.currentIndex) {
            return queued
        }

        val current = state.currentIndex ?: return null
        if (!state.shuffle) {
            val next = current + 1
            if (next < state.tracks.size) return next
            return if (state.loopMode == LoopMode.ALL) 0 else null
        }

        val position = state.shuffleOrder.indexOf(current)
        if (position < 0) return null
        return state.shuffleOrder.getOrNull(position + 1)
    }

    /** Advancing, and what it produced. */
    data class Advance(val state: PlaybackQueueState, val changed: Boolean)

    /**
     * Moves to the next track.
     *
     * [changed] is false when the queue is at its end with looping off — the caller should keep
     * playing what it has rather than reload anything.
     */
    fun next(
        state: PlaybackQueueState,
        weights: List<Double>,
        random: Random = Random.Default,
    ): Advance {
        if (state.isEmpty) return Advance(state, changed = false)

        val queued = state.playNextIndex
        if (queued != null && queued in state.tracks.indices && queued != state.currentIndex) {
            return Advance(state.copy(currentIndex = queued, playNextIndex = null), changed = true)
        }

        val current = state.currentIndex
            ?: return Advance(state.copy(currentIndex = 0), changed = true)

        if (!state.shuffle) {
            val next = current + 1
            return when {
                next < state.tracks.size -> Advance(state.copy(currentIndex = next), true)
                state.loopMode == LoopMode.ALL -> Advance(state.copy(currentIndex = 0), true)
                // Nothing after the last track, and not looping. Staying put is correct.
                else -> Advance(state, changed = false)
            }
        }
        return advanceShuffle(state, current, weights, random)
    }

    fun previous(
        state: PlaybackQueueState,
        weights: List<Double>,
        random: Random = Random.Default,
    ): Advance {
        if (state.isEmpty) return Advance(state, changed = false)
        val current = state.currentIndex
            ?: return Advance(state.copy(currentIndex = 0), changed = true)

        if (!state.shuffle) {
            val previous = (current - 1 + state.tracks.size) % state.tracks.size
            return Advance(state.copy(currentIndex = previous), changed = true)
        }

        val order = resolveShuffleOrder(state, current, weights, random)
        val position = order.indexOf(current).coerceAtLeast(0)
        val target = order[maxOf(0, position - 1)]
        return Advance(state.copy(shuffleOrder = order, currentIndex = target), changed = true)
    }

    /**
     * Clears the "play this next" jump once the track it pointed at has become current.
     *
     * [next] does this inline; the platform's armed hand-offs promote without going through it,
     * and a `playNextIndex` left pointing at the *current* track pins [peekNextIndex] there
     * forever — the queue stops advancing at all.
     */
    fun consumePlayNext(state: PlaybackQueueState, promoted: Int): PlaybackQueueState =
        if (state.playNextIndex == promoted) state.copy(playNextIndex = null) else state

    /** Moves to [index] directly — a tap on a row in Up Next. */
    fun jumpTo(state: PlaybackQueueState, index: Int): PlaybackQueueState =
        if (index in state.tracks.indices) {
            consumePlayNext(state.copy(currentIndex = index), index)
        } else {
            state
        }

    /**
     * The upcoming tracks in real play order, with the "play next" jump spliced to the front.
     *
     * Positions are returned alongside the files because a view needs them to remove an entry, and
     * re-deriving an index from a file is wrong the moment the same track appears twice.
     */
    fun upNext(state: PlaybackQueueState, limit: Int = 20): List<Entry> {
        val current = state.currentIndex ?: return emptyList()
        if (state.isEmpty) return emptyList()

        val position = state.shuffleOrder.indexOf(current)
        val rest = if (state.shuffle && position >= 0) {
            state.shuffleOrder.drop(position + 1).toMutableList()
        } else {
            ((current + 1) until state.tracks.size).toMutableList()
        }

        val queued = state.playNextIndex
        if (queued != null && queued in state.tracks.indices && queued != current) {
            rest.remove(queued)
            rest.add(0, queued)
        }

        return rest.take(limit).mapNotNull { index ->
            state.tracks.getOrNull(index)?.let { Entry(it, index) }
        }
    }

    data class Entry(val file: DriveFile, val index: Int)

    // MARK: - Windowed shuffle

    private fun resolveShuffleOrder(
        state: PlaybackQueueState,
        pinned: Int,
        weights: List<Double>,
        random: Random,
    ): List<Int> =
        if (state.shuffleOrder.contains(pinned)) state.shuffleOrder
        else Shuffle.seedWindow(state.tracks.size, pinned, weights, random)

    private fun advanceShuffle(
        state: PlaybackQueueState,
        from: Int,
        weights: List<Double>,
        random: Random,
    ): Advance {
        var order = resolveShuffleOrder(state, from, weights, random)
        val position = order.indexOf(from).coerceAtLeast(0)
        val nextPosition = position + 1

        if (nextPosition >= order.size) {
            order = Shuffle.growWindow(
                order, state.tracks.size, weights,
                loopOff = state.loopMode == LoopMode.OFF,
                advancingFrom = from,
                random = random,
            )
            if (nextPosition >= order.size) {
                // Window exhausted and nothing legal left to add — every track has played and
                // looping is off. Staying put is the honest answer.
                return Advance(state.copy(shuffleOrder = order), changed = false)
            }
        }

        order = refill(
            order, nextPosition, state.tracks.size, weights,
            loopOff = state.loopMode == LoopMode.OFF, random = random,
        )
        return Advance(
            state.copy(shuffleOrder = order, currentIndex = order[nextPosition]),
            changed = true,
        )
    }

    /**
     * Tops the window back up to a full [Shuffle.WINDOW_SIZE] ahead of [position].
     *
     * [Shuffle.growWindow] appends exactly one pick per call. Calling it once is fine while
     * advancing normally — each step adds one, so the window stays full — but wrong at the moment
     * the window is exhausted: reaching the last queued track with looping on then left Up Next
     * holding a single newly-picked track instead of a fresh twenty, which is the reported "when
     * the queue runs out it only shuffles in one more song".
     */
    private fun refill(
        order: List<Int>,
        position: Int,
        queueLength: Int,
        weights: List<Double>,
        loopOff: Boolean,
        random: Random,
    ): List<Int> {
        var result = order
        while (result.size - (position + 1) < Shuffle.WINDOW_SIZE - 1) {
            val grown = Shuffle.growWindow(
                result, queueLength, weights, loopOff, result[position], random
            )
            // The no-progress guard — without it this is an infinite loop in exactly the cases
            // `growWindow` is documented to decline to grow.
            if (grown.size == result.size) break
            result = grown
        }
        return result
    }
}
