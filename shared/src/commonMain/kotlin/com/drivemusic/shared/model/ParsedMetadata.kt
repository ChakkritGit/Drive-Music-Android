package com.drivemusic.shared.model

import kotlinx.serialization.Serializable

/**
 * Tags read from a downloaded file.
 *
 * Note what is *not* here: the cover art bytes. iOS keeps `artwork: Data?` inline on this struct
 * and that turned out to be a repeated source of memory problems — armed transitions, queue
 * sheets and list views all ended up holding megabytes of JPEG they only needed a title from.
 * Artwork is addressed separately here, by file id, so holding metadata never implies holding an
 * image.
 */
@Serializable
data class ParsedMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val durationSec: Double? = null,
)
