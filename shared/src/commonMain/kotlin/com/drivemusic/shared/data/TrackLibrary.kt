package com.drivemusic.shared.data

import com.drivemusic.shared.model.CachedTrack
import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.PlaybackSession
import com.drivemusic.shared.model.Playlist
import com.drivemusic.shared.model.RecentSource
import com.drivemusic.shared.recommendation.ListeningModel

/**
 * Everything the player needs to persist, as an interface.
 *
 * Declared here rather than in the Android module so the orchestration above it never depends on
 * Room — the same inversion the iOS app makes for the same reason. It also means the queue and
 * transition logic can be exercised against an in-memory fake, which is how the tests run.
 */
interface TrackLibrary {
    suspend fun listCachedTracks(): List<CachedTrack>

    /**
     * Just the ids of downloaded tracks.
     *
     * A separate call rather than mapping over [listCachedTracks], because a `CachedTrack` carries
     * its full parsed metadata and the callers that only want to know *which* tracks exist — the
     * analysis pass, the "is this downloaded" checks — would otherwise materialise all of it.
     */
    suspend fun listCachedTrackIds(): Set<String>

    suspend fun getCachedTrack(fileId: String): CachedTrack?
    suspend fun putCachedTrack(track: CachedTrack)
    suspend fun deleteCachedTrack(fileId: String)

    suspend fun listPlaylists(): List<Playlist>
    suspend fun createPlaylist(name: String): Playlist
    suspend fun deletePlaylist(id: String)
    suspend fun addTrackToPlaylist(id: String, file: DriveFile)
    suspend fun removeTrackFromPlaylist(id: String, fileId: String)

    /** Folders and playlists played recently, most recent first — the Home shelf. */
    suspend fun listRecentSources(limit: Int = 10): List<RecentSource>
    suspend fun recordRecentSource(source: com.drivemusic.shared.model.PlaySource, tracks: List<DriveFile>)

    suspend fun loadPlaybackSession(): PlaybackSession?
    suspend fun savePlaybackSession(session: PlaybackSession)

    suspend fun loadModel(): ListeningModel
    suspend fun saveModel(model: ListeningModel)

    suspend fun clearAll()
}

/** Where the downloaded audio actually lives. */
interface AudioFileStore {
    /** Writes [data] for [file] and returns its path relative to the store root. */
    suspend fun store(data: ByteArray, file: DriveFile): String

    /** An absolute URI the audio player can open. */
    suspend fun uri(relativePath: String): String

    suspend fun delete(relativePath: String)
    suspend fun totalBytes(): Long
    suspend fun clearAll()
}

/**
 * Supplies a currently-valid Drive access token.
 *
 * Refreshing is the implementation's problem, not the caller's — every call site here assumes the
 * token it gets back is usable right now.
 */
fun interface AccessTokenProvider {
    suspend fun freshAccessToken(): String
}
