package com.drivemusic.shared

import com.drivemusic.shared.model.DriveFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mirrors `DriveFileDisplayNameTests` on the iOS side, case for case. `displayName` is what every
 * list shows until a track's tags have been read, and the two platforms drifting apart here would
 * mean the same library reading differently on each.
 */
class DriveFileTest {
    private fun named(name: String) = DriveFile(id = "1", name = name, mimeType = "audio/mpeg")

    @Test
    fun stripsMixedAlphanumericExtensions() {
        assertEquals("Track", named("Track.mp3").displayName)
        assertEquals("Track", named("Track.m4a").displayName)
        assertEquals("Track", named("Track.mp4").displayName)
    }

    @Test
    fun stripsAllLetterExtensions() {
        assertEquals("Track", named("Track.flac").displayName)
        assertEquals("Track", named("Track.wav").displayName)
        assertEquals("Track", named("Track.opus").displayName)
    }

    @Test
    fun stripsLeadingTrackNumbers() {
        assertEquals("Track", named("01 Track.mp3").displayName)
        assertEquals("Track", named("07. Track.flac").displayName)
        assertEquals("Track", named("07 - Track.m4a").displayName)
        assertEquals("Track", named("07-Track.mp3").displayName)
        assertEquals("Track", named("12 - Track.mp3").displayName)
    }

    /**
     * Digits that open a real title are not a track number. They qualify as one only with an
     * explicit separator after them, or by being zero-padded.
     */
    @Test
    fun keepsTitlesThatOpenWithNumbers() {
        assertEquals("1979", named("1979.mp3").displayName)
        assertEquals("99 Luftballons", named("99 Luftballons.mp3").displayName)
        assertEquals("7 Nation Army", named("7 Nation Army.mp3").displayName)
        assertEquals("24 Hours", named("24 Hours.m4a").displayName)
    }

    @Test
    fun keepsDotsInsideTitles() {
        assertEquals("Mr. Brightside", named("Mr. Brightside").displayName)
        assertEquals("Mr. Brightside", named("Mr. Brightside.mp3").displayName)
    }

    @Test
    fun neverReturnsEmpty() {
        assertEquals("07", named("07.mp3").displayName)
        assertEquals(".mp3", named(".mp3").displayName)
    }
}
