package com.drivemusic.shared

import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.ParsedMetadata
import com.drivemusic.shared.recommendation.Features
import com.drivemusic.shared.recommendation.ListeningModel
import com.drivemusic.shared.recommendation.RecommendationModel
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecommendationModelTest {
    private val file = DriveFile(id = "abc", name = "01 Track.mp3", mimeType = "audio/mpeg")

    @Test
    fun defaultModelHasTheExpectedShape() {
        assertTrue(RecommendationModel.hasExpectedShape(RecommendationModel.createDefault()))
    }

    /**
     * The guard that prevents a crash-on-upgrade: a model persisted by a build with different
     * dimensions must be rejected, not indexed into.
     */
    @Test
    fun modelFromADifferentArchitectureIsRejected() {
        val wrong = ListeningModel(
            w1 = List(RecommendationModel.HIDDEN_SIZE) { List(3) { 0.0 } },
            b1 = List(RecommendationModel.HIDDEN_SIZE) { 0.0 },
            w2 = List(RecommendationModel.HIDDEN_SIZE) { 0.0 },
            b2 = 0.0,
            trainingEvents = 5,
            updatedAt = Instant.fromEpochSeconds(0),
        )
        assertFalse(RecommendationModel.hasExpectedShape(wrong))
    }

    @Test
    fun predictionIsBounded() {
        val model = RecommendationModel.createDefault()
        val features = Features.extract(file, null, Instant.fromEpochSeconds(1_700_000_000), TimeZone.UTC)
        val prediction = RecommendationModel.predict(model, features)
        assertTrue(prediction > 0.0 && prediction < 1.0, "sigmoid output must be in (0,1), got $prediction")
    }

    @Test
    fun trainingMovesThePredictionTowardTheLabel() {
        var model = RecommendationModel.createDefault()
        val features = Features.extract(file, null, Instant.fromEpochSeconds(1_700_000_000), TimeZone.UTC)
        val before = RecommendationModel.predict(model, features)

        repeat(50) { model = RecommendationModel.trainStep(model, features, label = 1.0) }
        val after = RecommendationModel.predict(model, features)

        assertTrue(after > before, "expected prediction to rise toward 1.0, went $before -> $after")
        assertEquals(50, model.trainingEvents)
    }

    @Test
    fun featureVectorIsOneHotPerGroup() {
        val meta = ParsedMetadata(artist = "Someone", album = "Something")
        val features = Features.extract(file, meta, Instant.fromEpochSeconds(1_700_000_000), TimeZone.UTC)

        assertEquals(Features.FEATURE_SIZE, features.size)
        // Bias + one per group.
        assertEquals(Features.groups.size.toDouble(), features.sum())
    }

    /**
     * The hash must never produce a negative bucket. `Int.MIN_VALUE` is the value that breaks a
     * naive `abs`, and it is reachable because the hash wraps.
     */
    @Test
    fun hashIsAlwaysInRange() {
        val samples = listOf("", "a", "unknown-artist", "polygenelubricants", "𝄞 emoji ✨", "x".repeat(500))
        for (sample in samples) {
            val bucket = Features.hashString(sample, Features.ARTIST_BUCKETS)
            assertTrue(bucket in 0 until Features.ARTIST_BUCKETS, "'$sample' -> $bucket")
        }
    }

    @Test
    fun weightedRandomIndexStaysInBounds() {
        assertEquals(0, RecommendationModel.weightedRandomIndex(emptyList<Double>()))
        repeat(200) {
            val index = RecommendationModel.weightedRandomIndex(listOf(0.0, 0.0, 5.0))
            assertTrue(index in 0..2)
        }
    }
}
