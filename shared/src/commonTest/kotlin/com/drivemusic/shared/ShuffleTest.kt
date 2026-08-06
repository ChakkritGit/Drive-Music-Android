package com.drivemusic.shared

import com.drivemusic.shared.playback.Shuffle
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShuffleTest {
    private val weights = List(10) { 1.0 }

    @Test
    fun seedWindowPinsTheCurrentTrackFirst() {
        val order = Shuffle.seedWindow(10, pinned = 3, weights = weights, random = Random(1))
        assertEquals(3, order.first())
        assertEquals(order.size, order.toSet().size, "no index may appear twice")
        assertEquals(10, order.size)
    }

    @Test
    fun seedWindowIsCappedAtWindowSize() {
        val order = Shuffle.seedWindow(500, pinned = 0, weights = List(500) { 1.0 }, random = Random(1))
        assertEquals(Shuffle.WINDOW_SIZE, order.size)
    }

    @Test
    fun growWindowStopsWhenEverythingIsUsedAndLoopIsOff() {
        val full = (0 until 10).toList()
        val grown = Shuffle.growWindow(full, 10, weights, loopOff = true, advancingFrom = 0)
        assertEquals(full, grown)
    }

    @Test
    fun growWindowAllowsRepeatsWhenLooping() {
        val full = (0 until 10).toList()
        val grown = Shuffle.growWindow(full, 10, weights, loopOff = false, advancingFrom = 0, random = Random(2))
        assertEquals(full.size + 1, grown.size)
        assertTrue(grown.last() != 0, "must not immediately repeat the track being advanced from")
    }

    @Test
    fun singleTrackQueueCannotGrow() {
        val order = listOf(0)
        assertEquals(order, Shuffle.growWindow(order, 1, listOf(1.0), loopOff = false, advancingFrom = 0))
    }

    // --- reindexForInsertion: the "queueing a track re-shuffled Up Next" regression ---

    @Test
    fun insertionPreservesExistingOrder() {
        val order = Shuffle.reindexForInsertion(listOf(2, 4, 0, 3, 1), removed = null, insertedAt = 3, currentIndex = 2)
        assertEquals(listOf(2, 3, 5, 0, 4, 1), order)
        assertEquals(listOf(2, 5, 0, 4, 1), order.filter { it != 3 })
    }

    @Test
    fun insertedTrackFollowsCurrentTrack() {
        val order = Shuffle.reindexForInsertion(listOf(2, 4, 0, 3, 1), removed = null, insertedAt = 3, currentIndex = 2)
        assertEquals(0, order.indexOf(2))
        assertEquals(3, order[1])
    }

    @Test
    fun movingAnAlreadyQueuedTrackLeavesNoDuplicate() {
        val order = Shuffle.reindexForInsertion(listOf(1, 4, 0, 3, 2), removed = 4, insertedAt = 2, currentIndex = 1)
        assertEquals(listOf(1, 2, 0, 4, 3), order)
        assertEquals(order.size, order.toSet().size, "no index may appear twice")
    }

    @Test
    fun emptyOrderStaysEmpty() {
        assertEquals(emptyList<Int>(), Shuffle.reindexForInsertion(emptyList(), null, 1, 0))
    }

    @Test
    fun insertionGoesFirstWhenCurrentTrackIsNotInTheOrder() {
        val order = Shuffle.reindexForInsertion(listOf(4, 0, 3), removed = null, insertedAt = 1, currentIndex = 2)
        assertEquals(1, order.first())
        assertEquals(listOf(1, 5, 0, 4), order)
    }
}
