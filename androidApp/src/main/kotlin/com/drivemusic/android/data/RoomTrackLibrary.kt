package com.drivemusic.android.data

import com.drivemusic.shared.data.TrackLibrary
import com.drivemusic.shared.model.CachedTrack
import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.ParsedMetadata
import com.drivemusic.shared.model.PlaybackSession
import com.drivemusic.shared.model.Playlist
import com.drivemusic.shared.recommendation.ListeningModel
import com.drivemusic.shared.recommendation.RecommendationModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class RoomTrackLibrary(
    private val dao: LibraryDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TrackLibrary {

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun CachedTrackEntity.toModel() = CachedTrack(
        fileId = fileId,
        relativeFilePath = relativeFilePath,
        mimeType = mimeType,
        driveMeta = json.decodeFromString(DriveFile.serializer(), driveMetaJson),
        parsedMeta = json.decodeFromString(ParsedMetadata.serializer(), parsedMetaJson),
        cachedAt = Instant.fromEpochMilliseconds(cachedAtEpochMs),
        loudnessGain = loudnessGain,
    )

    override suspend fun listCachedTracks(): List<CachedTrack> = io {
        dao.allTracks().map { it.toModel() }
    }

    override suspend fun listCachedTrackIds(): Set<String> = io { dao.allTrackIds().toSet() }

    override suspend fun getCachedTrack(fileId: String): CachedTrack? = io {
        dao.track(fileId)?.toModel()
    }

    override suspend fun putCachedTrack(track: CachedTrack) = io {
        dao.putTrack(
            CachedTrackEntity(
                fileId = track.fileId,
                relativeFilePath = track.relativeFilePath,
                mimeType = track.mimeType,
                driveMetaJson = json.encodeToString(DriveFile.serializer(), track.driveMeta),
                parsedMetaJson = json.encodeToString(ParsedMetadata.serializer(), track.parsedMeta),
                cachedAtEpochMs = track.cachedAt.toEpochMilliseconds(),
                loudnessGain = track.loudnessGain,
            )
        )
    }

    override suspend fun deleteCachedTrack(fileId: String) = io {
        dao.deleteTrack(fileId)
        dao.deleteArtwork(fileId)
    }

    /** Cover art, stored apart from the metadata so holding one never implies holding the other. */
    suspend fun artwork(fileId: String): ByteArray? = io { dao.artwork(fileId) }

    suspend fun putArtwork(fileId: String, bytes: ByteArray) = io {
        dao.putArtwork(ArtworkEntity(fileId, bytes))
    }

    override suspend fun listPlaylists(): List<Playlist> = io {
        dao.allPlaylists().map { it.toModel() }
    }

    private fun PlaylistEntity.toModel() = Playlist(
        id = id,
        name = name,
        tracks = json.decodeFromString(TRACKS, tracksJson),
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
    )

    override suspend fun createPlaylist(name: String): Playlist = io {
        val playlist = Playlist(
            id = Uuid.random().toString(),
            name = name,
            createdAt = kotlinx.datetime.Clock.System.now(),
        )
        dao.putPlaylist(playlist.toEntity())
        playlist
    }

    private fun Playlist.toEntity() = PlaylistEntity(
        id = id,
        name = name,
        tracksJson = json.encodeToString(TRACKS, tracks),
        createdAtEpochMs = createdAt.toEpochMilliseconds(),
    )

    override suspend fun deletePlaylist(id: String) = io { dao.deletePlaylist(id) }

    override suspend fun addTrackToPlaylist(id: String, file: DriveFile) = io {
        val existing = dao.playlist(id)?.toModel() ?: return@io
        if (existing.tracks.any { it.id == file.id }) return@io
        dao.putPlaylist(existing.copy(tracks = existing.tracks + file).toEntity())
    }

    override suspend fun removeTrackFromPlaylist(id: String, fileId: String) = io {
        val existing = dao.playlist(id)?.toModel() ?: return@io
        dao.putPlaylist(existing.copy(tracks = existing.tracks.filter { it.id != fileId }).toEntity())
    }

    override suspend fun loadPlaybackSession(): PlaybackSession? = io {
        dao.singleton(KEY_SESSION)?.let {
            runCatching { json.decodeFromString(PlaybackSession.serializer(), it) }.getOrNull()
        }
    }

    override suspend fun savePlaybackSession(session: PlaybackSession) = io {
        dao.putSingleton(
            SingletonEntity(KEY_SESSION, json.encodeToString(PlaybackSession.serializer(), session))
        )
    }

    /**
     * The stored model, or a fresh one when what is stored no longer fits this build.
     *
     * The shape check is not optional. `RecommendationModel.forward` indexes rows by the feature
     * count with no bounds check of its own, so a model persisted by a build with a different
     * feature layout does not degrade — it throws, on the first prediction after the upgrade,
     * which is every track change, and restarting does not help because the bad model is on disk.
     */
    override suspend fun loadModel(): ListeningModel = io {
        val stored = dao.singleton(KEY_MODEL)
            ?.let { runCatching { json.decodeFromString(ListeningModel.serializer(), it) }.getOrNull() }
        stored?.takeIf { RecommendationModel.hasExpectedShape(it) } ?: RecommendationModel.createDefault()
    }

    override suspend fun saveModel(model: ListeningModel) = io {
        dao.putSingleton(
            SingletonEntity(KEY_MODEL, json.encodeToString(ListeningModel.serializer(), model))
        )
    }

    override suspend fun clearAll() = io {
        dao.clearTracks()
        dao.clearArtwork()
        dao.clearPlaylists()
        dao.clearSingletons()
    }

    private companion object {
        const val KEY_SESSION = "session"
        const val KEY_MODEL = "model"
        val TRACKS = kotlinx.serialization.builtins.ListSerializer(DriveFile.serializer())
    }
}
