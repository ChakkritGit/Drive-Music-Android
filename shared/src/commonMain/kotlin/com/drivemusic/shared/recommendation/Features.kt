package com.drivemusic.shared.recommendation

import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.ParsedMetadata
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Same bucket sizes, same hash function, same feature layout as the Swift and TypeScript
 * versions — that equivalence is the whole point, since a model's weights are meaningless
 * against a different feature layout.
 */
object Features {
    const val TIME_BUCKETS = 4
    const val DAY_BUCKETS = 2
    const val ARTIST_BUCKETS = 16
    const val ALBUM_BUCKETS = 16
    const val TRACK_BUCKETS = 8

    const val FEATURE_SIZE =
        1 + TIME_BUCKETS + DAY_BUCKETS + ARTIST_BUCKETS + ALBUM_BUCKETS + TRACK_BUCKETS

    data class Group(val label: String, val size: Int)

    /** How [FEATURE_SIZE]'s dimensions are laid out, in order. */
    val groups: List<Group> = listOf(
        Group("Bias", 1),
        Group("Time of day", TIME_BUCKETS),
        Group("Weekday", DAY_BUCKETS),
        Group("Artist", ARTIST_BUCKETS),
        Group("Album", ALBUM_BUCKETS),
        Group("Track", TRACK_BUCKETS),
    )

    /**
     * The 32-bit-wraparound `hash * 31 + charCode` used by all three platforms.
     *
     * Kotlin's `Int` arithmetic already wraps, and iterating a `String` by `Char` yields UTF-16
     * code units — exactly what JS's `charCodeAt` and Swift's `.utf16` give — so this is
     * bit-identical to both without any explicit truncation.
     *
     * The magnitude is taken via `Long`, not `abs`. The hash can legitimately land on
     * `Int.MIN_VALUE`, whose negation is not representable: Swift's `abs` traps outright there,
     * and Kotlin's `absoluteValue` silently returns the value *still negative*, which then makes
     * `% buckets` produce a negative index and throws on the array write below. Widening first is
     * what makes the value representable, and it agrees with `abs` everywhere else.
     */
    fun hashString(value: String, buckets: Int): Int {
        var hash = 0
        for (char in value) {
            hash = hash * 31 + char.code
        }
        return (hash.toLong().let { if (it < 0) -it else it } % buckets).toInt()
    }

    fun timeBucket(hour: Int): Int = when {
        hour < 6 -> 0   // night
        hour < 12 -> 1  // morning
        hour < 18 -> 2  // afternoon
        else -> 3       // evening
    }

    /**
     * Turns a track plus listening context into a fixed-length feature vector via hashing, so
     * unbounded artist/album/track vocab stays a small dense array.
     *
     * [timeZone] is the *local* zone by default, matching iOS's `Calendar.current` — "time of
     * day" is a fact about the listener, not about UTC, and using the wrong zone would shift
     * every listen into a neighbouring bucket.
     */
    fun extract(
        file: DriveFile,
        meta: ParsedMetadata?,
        at: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): DoubleArray {
        val features = DoubleArray(FEATURE_SIZE)
        var offset = 0

        features[offset] = 1.0 // bias
        offset += 1

        val local = at.toLocalDateTime(timeZone)
        features[offset + timeBucket(local.hour)] = 1.0
        offset += TIME_BUCKETS

        // Swift reads `Calendar.component(.weekday)` where 1 = Sunday and 7 = Saturday; the
        // equivalent here is the day-of-week enum, so the *names* are compared rather than
        // numbers, which are indexed differently (ISO: Monday = 1).
        val isWeekend = local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY
        features[offset + if (isWeekend) 1 else 0] = 1.0
        offset += DAY_BUCKETS

        features[offset + hashString(meta?.artist ?: "unknown-artist", ARTIST_BUCKETS)] = 1.0
        offset += ARTIST_BUCKETS

        features[offset + hashString(meta?.album ?: "unknown-album", ALBUM_BUCKETS)] = 1.0
        offset += ALBUM_BUCKETS

        features[offset + hashString(file.id, TRACK_BUCKETS)] = 1.0

        return features
    }
}
