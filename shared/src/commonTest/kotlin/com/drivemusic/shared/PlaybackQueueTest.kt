package com.drivemusic.shared

import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.LoopMode
import com.drivemusic.shared.model.PlaySource
import com.drivemusic.shared.model.PlaybackSession
import com.drivemusic.shared.playback.PlaybackQueue
import com.drivemusic.shared.playback.PlaybackQueueState
import com.drivemusic.shared.playback.Shuffle
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every rule here corresponds to something that was reported as broken on iOS, where this logic
 * lives entangled with the audio engine and could not be tested without one. That entanglement is
 * why the bugs survived, and this file is the reason they cannot come back on either platform.
 */
class PlaybackQueueTest {
    private fun tracks(count: Int) = List(count) {
        DriveFile(id = "t$it", name = "Track $it.mp3", mimeType = "audio/mpeg")
    }

    private val weights = List(10) { 1.0 }

    private fun sequential(count: Int = 5, current: Int = 0) = PlaybackQueueState(
        tracks = tracks(count),
        currentIndex = current,
    )

    private fun shuffled(count: Int = 10, current: Int = 0, seed: Int = 1): PlaybackQueueState {
        val base = PlaybackQueueState(tracks = tracks(count), currentIndex = current, shuffle = true)
        return base.copy(
            shuffleOrder = Shuffle.seedWindow(count, current, weights, Random(seed))
        )
    }

    // MARK: - Sequential transport

    @Test
    fun nextAdvancesAndStopsAtTheEnd() {
        var state = sequential(current = 3)
        state = PlaybackQueue.next(state, weights).state
        assertEquals(4, state.currentIndex)

        val atEnd = PlaybackQueue.next(state, weights)
        assertEquals(false, atEnd.changed, "there is nothing after the last track with loop off")
        assertEquals(4, atEnd.state.currentIndex)
    }

    @Test
    fun loopAllWrapsAround() {
        val state = sequential(current = 4).copy(loopMode = LoopMode.ALL)
        val advanced = PlaybackQueue.next(state, weights)
        assertTrue(advanced.changed)
        assertEquals(0, advanced.state.currentIndex)
    }

    @Test
    fun loopOneNeverReportsANextTrack() {
        val state = sequential(current = 1).copy(loopMode = LoopMode.ONE)
        assertNull(PlaybackQueue.peekNextIndex(state), "loop-one repeats rather than advancing")
    }

    @Test
    fun previousWrapsBackwards() {
        val state = sequential(current = 0)
        assertEquals(4, PlaybackQueue.previous(state, weights).state.currentIndex)
    }

    // MARK: - Queueing a track

    /** The reported bug: the queued track was skipped whenever the current one ran to its end. */
    @Test
    fun aQueuedTrackIsWhatPlaysNext() {
        val state = PlaybackQueue.addToQueue(sequential(current = 0), tracks(6)[5])

        assertEquals(1, state.playNextIndex)
        assertEquals(1, PlaybackQueue.peekNextIndex(state), "peek must agree with next()")
        assertEquals(1, PlaybackQueue.next(state, weights).state.currentIndex)
    }

    @Test
    fun peekAndNextAgreeUnderShuffleToo() {
        val state = PlaybackQueue.addToQueue(shuffled(current = 0), tracks(12)[11])
        assertEquals(PlaybackQueue.peekNextIndex(state), PlaybackQueue.next(state, weights).state.currentIndex)
    }

    /** The other half of the same report: Up Next re-randomised itself on every insertion. */
    @Test
    fun queueingATrackDoesNotReshuffleUpNext() {
        val before = shuffled(count = 10, current = 0)
        val upNextBefore = PlaybackQueue.upNext(before).map { it.file.id }

        val after = PlaybackQueue.addToQueue(before, DriveFile("new", "New.mp3", "audio/mpeg"))
        val upNextAfter = PlaybackQueue.upNext(after).map { it.file.id }

        assertEquals("new", upNextAfter.first(), "the queued track comes first")
        assertEquals(
            upNextBefore,
            upNextAfter.drop(1).take(upNextBefore.size),
            "the established order behind it must be untouched",
        )
    }

    @Test
    fun queueingAnAlreadyQueuedTrackMovesItRatherThanDuplicating() {
        val state = PlaybackQueue.addToQueue(sequential(count = 5, current = 0), tracks(5)[4])

        assertEquals(5, state.tracks.size)
        assertEquals(1, state.tracks.count { it.id == "t4" })
        assertEquals("t4", state.tracks[1].id)
    }

    /** Queueing the track that is already playing is meaningless and must not disturb anything. */
    @Test
    fun queueingTheCurrentTrackIsANoOp() {
        val state = sequential(current = 2)
        assertEquals(state, PlaybackQueue.addToQueue(state, state.tracks[2]))
    }

    @Test
    fun queueingIntoAnEmptyQueueStartsPlaying() {
        val state = PlaybackQueue.addToQueue(PlaybackQueueState(), tracks(1)[0])
        assertEquals(0, state.currentIndex)
        assertEquals(1, state.tracks.size)
        assertNull(state.playNextIndex)
    }

    /**
     * A `playNextIndex` left pointing at the track that just became current pins `peekNextIndex`
     * there forever — the queue stops advancing at all.
     */
    @Test
    fun consumingThePlayNextJumpUnpinsTheQueue() {
        val queued = PlaybackQueue.addToQueue(sequential(current = 0), tracks(6)[5])
        val promoted = PlaybackQueue.consumePlayNext(queued.copy(currentIndex = 1), 1)

        assertNull(promoted.playNextIndex)
        assertEquals(2, PlaybackQueue.peekNextIndex(promoted))
    }

    // MARK: - Removing

    @Test
    fun removingATrackDoesNotReshuffleTheRest() {
        val before = shuffled(count = 10, current = 0)
        val toRemove = PlaybackQueue.upNext(before)[3].index
        val expected = PlaybackQueue.upNext(before).map { it.file.id }.filterIndexed { i, _ -> i != 3 }

        val after = PlaybackQueue.removeFromQueue(before, toRemove)
        assertEquals(expected, PlaybackQueue.upNext(after).map { it.file.id })
    }

    @Test
    fun removingTheCurrentTrackIsRefused() {
        val state = sequential(current = 2)
        assertEquals(state, PlaybackQueue.removeFromQueue(state, 2))
    }

    @Test
    fun removingBeforeTheCurrentTrackKeepsItCurrent() {
        val state = PlaybackQueue.removeFromQueue(sequential(current = 3), 1)
        assertEquals(2, state.currentIndex)
        assertEquals("t3", state.currentTrack?.id, "the same track must still be playing")
    }

    // MARK: - Shuffle window

    @Test
    fun theWindowStaysToppedUpWhileAdvancing() {
        var state = shuffled(count = 60, current = 0)
        repeat(30) { state = PlaybackQueue.next(state, List(60) { 1.0 }).state }

        val ahead = PlaybackQueue.upNext(state, limit = 100).size
        assertTrue(ahead >= Shuffle.WINDOW_SIZE - 1, "only $ahead tracks queued ahead")
    }

    /** The reported "when the queue runs out it only shuffles in one more song". */
    @Test
    fun exhaustingTheWindowWithLoopingRefillsItFully() {
        var state = shuffled(count = 25, current = 0).copy(loopMode = LoopMode.ALL)
        repeat(30) { state = PlaybackQueue.next(state, List(25) { 1.0 }).state }

        assertTrue(
            PlaybackQueue.upNext(state, limit = 100).size >= Shuffle.WINDOW_SIZE - 1,
            "the window was not refilled after being exhausted",
        )
    }

    @Test
    fun shuffleWithLoopOffStopsOnceEveryTrackHasPlayed() {
        var state = shuffled(count = 8, current = 0)
        var advances = 0
        repeat(20) {
            val advance = PlaybackQueue.next(state, List(8) { 1.0 })
            state = advance.state
            if (advance.changed) advances++
        }
        assertEquals(7, advances, "eight tracks means seven advances, then nothing left")
    }

    @Test
    fun turningShuffleOffClearsTheWindow() {
        val on = shuffled(current = 0)
        val off = PlaybackQueue.setShuffle(on, enabled = false, weights = weights)
        assertTrue(off.shuffleOrder.isEmpty())
        assertEquals(1, PlaybackQueue.peekNextIndex(off), "sequential order resumes")
    }

    @Test
    fun turningShuffleOnPinsTheCurrentTrack() {
        val state = PlaybackQueue.setShuffle(
            sequential(count = 10, current = 4), enabled = true, weights = weights
        )
        assertEquals(4, state.shuffleOrder.first())
    }

    // MARK: - Up Next

    @Test
    fun upNextIsCappedAndInPlayOrder() {
        val state = sequential(count = 50, current = 0)
        val upNext = PlaybackQueue.upNext(state, limit = 20)

        assertEquals(20, upNext.size)
        assertEquals(listOf(1, 2, 3), upNext.take(3).map { it.index })
    }

    @Test
    fun upNextIsEmptyWithNothingPlaying() {
        assertTrue(PlaybackQueue.upNext(PlaybackQueueState()).isEmpty())
    }

    @Test
    fun jumpingToATrackMakesItCurrent() {
        val state = PlaybackQueue.jumpTo(sequential(current = 0), 3)
        assertEquals(3, state.currentIndex)
    }

    // MARK: - Session round trip

    @Test
    fun aSessionRestoresTheQueueAndItsShuffleWindow() {
        val original = shuffled(count = 12, current = 5).copy(loopMode = LoopMode.ALL)
        val session = assertNotNull(
            PlaybackSession.from(
                original,
                source = PlaySource("f1", "Folder", PlaySource.Kind.FOLDER),
                progress = 42.0,
                volume = 0.8,
            )
        )
        val restored = session.toQueueState()

        assertEquals(original.tracks, restored.tracks)
        assertEquals(original.currentIndex, restored.currentIndex)
        assertEquals(original.shuffle, restored.shuffle)
        assertEquals(
            original.shuffleOrder,
            restored.shuffleOrder,
            "a relaunch must not re-roll a random order for a session already under way",
        )
        assertEquals(original.loopMode, restored.loopMode)
    }

    /** "Play this next" is about the current moment, not the queue — restoring it days later is wrong. */
    @Test
    fun aSessionDoesNotRestoreThePlayNextJump() {
        val queued = PlaybackQueue.addToQueue(sequential(current = 0), tracks(6)[5])
        val session = assertNotNull(PlaybackSession.from(queued, null, 0.0, 1.0))
        assertNull(session.toQueueState().playNextIndex)
    }

    @Test
    fun anEmptyQueueHasNoSessionToSave() {
        assertNull(PlaybackSession.from(PlaybackQueueState(), null, 0.0, 1.0))
    }
}
