package com.drivemusic.shared.model

import kotlinx.serialization.Serializable

const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

/**
 * Ported from `DriveFile` in the iOS app's `DriveMusicCore`, which in turn mirrors the web app's
 * `src/types/index.ts`. Field names and nullability are kept identical across all three so a
 * payload from the Drive API deserializes the same everywhere.
 */
@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: String? = null,
    val modifiedTime: String? = null,
    val thumbnailLink: String? = null,
    val iconLink: String? = null,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME_TYPE

    /**
     * What to show when a track has no parsed title yet — a track only gets real metadata once
     * it has been downloaded and its tags read, so for everything not yet cached (most of a
     * library, most of the time) the file name is all there is.
     *
     * Raw [name] is a poor label for that: it carries the extension, and files that came off a
     * download or a CD rip routinely carry a track number too. Both are noise no music app shows.
     */
    val displayName: String
        get() {
            var result = name

            // Extension: only the last component, and only if it looks like one — a title with a
            // dot in it ("Mr. Brightside") must survive untouched, so require a short
            // alphanumeric tail.
            val dotIndex = result.lastIndexOf('.')
            if (dotIndex > 0) {
                val ext = result.substring(dotIndex + 1)
                // Every character alphanumeric. Requiring the tail to be *entirely* letters or
                // *entirely* digits — which is what the iOS version did until this was ported —
                // fails on `mp3`, `m4a` and `mp4`, the three commonest audio extensions there
                // are, so those kept their extension in every list.
                val looksLikeExtension = ext.length in 1..5 && ext.all { it.isLetterOrDigit() }
                if (looksLikeExtension) result = result.substring(0, dotIndex)
            }

            // Leading track number: "07 ", "07. ", "07 - ", "07-".
            //
            // Two ways to qualify, because "track number or start of the title?" has no answer
            // from the digits alone: either an explicit separator follows them, or they're
            // zero-padded, which is the convention rips use and titles don't. Matching on
            // whitespace alone (the iOS version's original rule) also matches every title that
            // opens with a number, so "99 Luftballons" displayed as "Luftballons".
            val trimmed = result.trim()
            val numbered = TRACK_NUMBER_PREFIX.find(trimmed)
            if (numbered != null) {
                val stripped = trimmed.removeRange(numbered.range).trim()
                if (stripped.isNotEmpty()) return stripped
            }
            return trimmed
        }

    private companion object {
        val TRACK_NUMBER_PREFIX = Regex("""^(?:\d{1,2}\s*[-–._]\s*|0\d\s+)""")
    }
}
