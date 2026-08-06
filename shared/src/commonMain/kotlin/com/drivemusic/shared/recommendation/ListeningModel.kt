package com.drivemusic.shared.recommendation

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * A small online-trained 2-layer neural net (tanh hidden layer, sigmoid output) over hashed
 * listening-context features. Ported from the iOS app, which ported it from the web app — the
 * arithmetic is identical in all three so a model trained on one platform stays meaningful if the
 * history ever moves.
 */
@Serializable
data class ListeningModel(
    val w1: List<List<Double>>,
    val b1: List<Double>,
    val w2: List<Double>,
    val b2: Double,
    val trainingEvents: Int,
    val updatedAt: Instant,
)

/** One training step's record: what the model predicted beforehand vs. what actually happened. */
@Serializable
data class ModelEvent(
    val id: String,
    val trackId: String,
    val title: String,
    val fraction: Double,
    val predicted: Double,
    val at: Instant,
)
