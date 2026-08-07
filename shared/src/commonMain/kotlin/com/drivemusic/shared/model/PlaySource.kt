package com.drivemusic.shared.model

import kotlinx.serialization.Serializable

/** Where a queue came from — a folder, a playlist, or one of the generated collections. */
@Serializable
data class PlaySource(
    val id: String,
    val name: String,
    val kind: Kind,
) {
    @Serializable
    enum class Kind { FOLDER, PLAYLIST, LIBRARY, GENERATED }
}
