package com.drivemusic.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** A track that has been downloaded and is playable offline. */
@Serializable
data class CachedTrack(
    val fileId: String,
    /** Path relative to the audio store's root — never absolute, so the store can move. */
    val relativeFilePath: String,
    val mimeType: String,
    val driveMeta: DriveFile,
    val parsedMeta: ParsedMetadata,
    val cachedAt: Instant,
    /** Replay-gain style multiplier, or null when the track has not been measured. */
    val loudnessGain: Double? = null,
) {
    val displayTitle: String get() = parsedMeta.title ?: driveMeta.displayName
}

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<DriveFile> = emptyList(),
    val createdAt: Instant,
)

/** A folder or playlist the user played from recently. */
@Serializable
data class RecentSource(
    val source: PlaySource,
    val tracks: List<DriveFile>,
    val lastPlayedAt: Instant,
    val playCount: Int,
)
