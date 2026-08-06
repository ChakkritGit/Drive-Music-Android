package com.drivemusic.shared.recommendation

import kotlinx.datetime.Clock
import kotlin.math.exp
import kotlin.math.tanh
import kotlin.random.Random

object RecommendationModel {
    const val HIDDEN_SIZE = 12
    private const val LEARNING_RATE = 0.05

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    /**
     * Small random hidden-layer init breaks symmetry between hidden units — the output layer can
     * safely start at zero.
     */
    fun createDefault(random: Random = Random.Default): ListeningModel = ListeningModel(
        w1 = List(HIDDEN_SIZE) {
            List(Features.FEATURE_SIZE) { (random.nextDouble() - 0.5) * 0.2 }
        },
        b1 = List(HIDDEN_SIZE) { 0.0 },
        w2 = List(HIDDEN_SIZE) { 0.0 },
        b2 = 0.0,
        trainingEvents = 0,
        updatedAt = Clock.System.now(),
    )

    /**
     * Whether [model]'s matrices match the architecture this build compiles against.
     *
     * [forward] indexes `w1`'s rows by the feature count and `b1`/`w2` by `w1.count`, with no
     * bounds checks of its own — so a model persisted by a build with a different
     * [Features.FEATURE_SIZE] or [HIDDEN_SIZE] doesn't degrade, it throws, on the first
     * prediction after the upgrade. That is every track change, and restarting doesn't help
     * because the bad model is on disk.
     *
     * Any of those constants moving is a deliberate architecture change and trained weights
     * cannot carry across one, so the honest answer is to start fresh. Anything loading a stored
     * model must run it through this first.
     */
    fun hasExpectedShape(model: ListeningModel): Boolean =
        model.w1.size == HIDDEN_SIZE &&
            model.w1.all { it.size == Features.FEATURE_SIZE } &&
            model.b1.size == HIDDEN_SIZE &&
            model.w2.size == HIDDEN_SIZE

    private class ForwardPass(val hidden: DoubleArray, val output: Double)

    private fun forward(model: ListeningModel, features: DoubleArray): ForwardPass {
        val hidden = DoubleArray(model.w1.size) { h ->
            val row = model.w1[h]
            var sum = model.b1[h]
            for (i in features.indices) sum += row[i] * features[i]
            tanh(sum)
        }
        var outSum = model.b2
        for (h in hidden.indices) outSum += model.w2[h] * hidden[h]
        return ForwardPass(hidden, sigmoid(outSum))
    }

    /** Predicted "how much of this track you'll listen to", in [0, 1]. */
    fun predict(model: ListeningModel, features: DoubleArray): Double =
        forward(model, features).output

    /** One online backprop step toward [label] (the fraction of the track actually played). */
    fun trainStep(model: ListeningModel, features: DoubleArray, label: Double): ListeningModel {
        val pass = forward(model, features)
        val outputError = label - pass.output

        val w2 = model.w2.toMutableList()
        val hiddenErrors = DoubleArray(pass.hidden.size)
        for (h in pass.hidden.indices) {
            // d(tanh)/dx = 1 - tanh(x)^2
            hiddenErrors[h] = outputError * model.w2[h] * (1 - pass.hidden[h] * pass.hidden[h])
            w2[h] += LEARNING_RATE * outputError * pass.hidden[h]
        }
        val b2 = model.b2 + LEARNING_RATE * outputError

        val w1 = model.w1.map { it.toMutableList() }
        for (h in w1.indices) {
            for (i in features.indices) {
                w1[h][i] += LEARNING_RATE * hiddenErrors[h] * features[i]
            }
        }
        val b1 = model.b1.mapIndexed { h, b -> b + LEARNING_RATE * hiddenErrors[h] }

        return ListeningModel(
            w1 = w1,
            b1 = b1,
            w2 = w2,
            b2 = b2,
            trainingEvents = model.trainingEvents + 1,
            updatedAt = Clock.System.now(),
        )
    }

    /**
     * Weighted random pick — index i is chosen with probability proportional to `weights[i]`,
     * floored so a zero-weight track is never impossible, just unlikely. Every random pick in the
     * shuffle and recommendation paths goes through this; none fall back to a uniform pick.
     */
    fun weightedRandomIndex(weights: List<Double>, random: Random = Random.Default): Int {
        if (weights.isEmpty()) return 0
        val floored = weights.map { maxOf(it, 0.001) }
        val total = floored.sum()
        var r = random.nextDouble() * total
        for (i in floored.indices) {
            r -= floored[i]
            if (r <= 0) return i
        }
        return floored.size - 1
    }
}
