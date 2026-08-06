package com.drivemusic.shared.playback

import com.drivemusic.shared.recommendation.RecommendationModel
import kotlin.random.Random

/**
 * A queue-index permutation built lazily in a sliding window of [WINDOW_SIZE], weighted by the
 * recommendation model's predictions, instead of shuffling a potentially huge library up front.
 */
object Shuffle {
    const val WINDOW_SIZE = 20

    private fun pickWeighted(candidates: List<Int>, weights: List<Double>, random: Random): Int {
        val candidateWeights = candidates.map {
            maxOf(weights.getOrElse(it) { 0.5 }, 0.001)
        }
        return candidates[RecommendationModel.weightedRandomIndex(candidateWeights, random)]
    }

    /**
     * The initial window: `[pinned, up to WINDOW_SIZE - 1 more weighted-random picks]`, rather
     * than a full permutation.
     */
    fun seedWindow(
        queueLength: Int,
        pinned: Int,
        weights: List<Double>,
        random: Random = Random.Default,
    ): List<Int> {
        val order = mutableListOf(pinned)
        val used = mutableSetOf(pinned)
        val target = minOf(queueLength, WINDOW_SIZE)
        while (order.size < target) {
            val candidates = (0 until queueLength).filter { it !in used }
            if (candidates.isEmpty()) break
            val pick = pickWeighted(candidates, weights, random)
            order.add(pick)
            used.add(pick)
        }
        return order
    }

    /**
     * Appends exactly one more weighted-random pick, excluding whatever is already in [order] —
     * called whenever advancing leaves fewer than [WINDOW_SIZE] tracks queued ahead, so Up Next
     * stays topped up. Once every track has appeared, loop-off leaves it as-is; loop-all starts
     * allowing repeats, excluding only the track being advanced from.
     */
    fun growWindow(
        order: List<Int>,
        queueLength: Int,
        weights: List<Double>,
        loopOff: Boolean,
        advancingFrom: Int,
        random: Random = Random.Default,
    ): List<Int> {
        val used = order.toSet()
        var candidates = (0 until queueLength).filter { it !in used }
        if (candidates.isEmpty()) {
            if (loopOff) return order
            candidates = (0 until queueLength).filter { it != advancingFrom }
            if (candidates.isEmpty()) return order // only one track total — nothing to add
        }
        return order + pickWeighted(candidates, weights, random)
    }

    /**
     * Maps a shuffle order through a queue splice — the track's old position removed (if it had
     * one), everything after each edit shifted, and the new position slotted in directly behind
     * the current track.
     *
     * This exists because clearing the order instead was a real, reported bug: queueing one track
     * made the next skip seed an entirely fresh random window, which reads as "adding a song
     * re-shuffled everything". An empty order means shuffle is off, and stays empty.
     */
    fun reindexForInsertion(
        order: List<Int>,
        removed: Int?,
        insertedAt: Int,
        currentIndex: Int,
    ): List<Int> {
        if (order.isEmpty()) return emptyList()
        var result = order
        if (removed != null) {
            result = result.filter { it != removed }.map { if (it > removed) it - 1 else it }
        }
        result = result.map { if (it >= insertedAt) it + 1 else it }

        val position = result.indexOf(currentIndex)
        return if (position >= 0) {
            result.toMutableList().apply { add(position + 1, insertedAt) }
        } else {
            listOf(insertedAt) + result
        }
    }
}
