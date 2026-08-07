package com.drivemusic.android.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * Room's view of what is stored.
 *
 * The nested structures — `DriveFile`, `ParsedMetadata`, a playlist's track list — are held as
 * JSON columns rather than as related tables. They are only ever read and written whole, nothing
 * queries inside them, and a relational shape would buy joins nobody performs at the cost of a
 * migration every time a field moves.
 *
 * The one thing deliberately *not* stored here is cover art. On iOS it sits inline on the
 * metadata, and that single decision caused three separate memory problems — list views, the queue
 * sheet and armed transitions all ended up holding megabytes of JPEG they only wanted a title
 * from. Artwork lives in its own table, fetched by id only when something is going to draw it.
 */
@Entity(tableName = "cached_tracks")
data class CachedTrackEntity(
    @PrimaryKey val fileId: String,
    val relativeFilePath: String,
    val mimeType: String,
    val driveMetaJson: String,
    val parsedMetaJson: String,
    val cachedAtEpochMs: Long,
    val loudnessGain: Double?,
)

@Entity(tableName = "artwork")
data class ArtworkEntity(
    @PrimaryKey val fileId: String,
    val bytes: ByteArray,
) {
    // ByteArray breaks data-class equality, and Room warns about it. Neither matters here — this
    // is never compared — but the generated equals would be wrong if it ever were.
    override fun equals(other: Any?): Boolean =
        this === other || (other is ArtworkEntity && fileId == other.fileId)

    override fun hashCode(): Int = fileId.hashCode()
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val tracksJson: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "singletons")
data class SingletonEntity(
    @PrimaryKey val key: String,
    val json: String,
)

class Converters {
    @TypeConverter fun fromBytes(value: ByteArray?): ByteArray? = value
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM cached_tracks ORDER BY cachedAtEpochMs DESC")
    suspend fun allTracks(): List<CachedTrackEntity>

    /** Ids only — see `TrackLibrary.listCachedTrackIds` for why this is a separate query. */
    @Query("SELECT fileId FROM cached_tracks")
    suspend fun allTrackIds(): List<String>

    @Query("SELECT * FROM cached_tracks WHERE fileId = :fileId LIMIT 1")
    suspend fun track(fileId: String): CachedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTrack(track: CachedTrackEntity)

    @Query("DELETE FROM cached_tracks WHERE fileId = :fileId")
    suspend fun deleteTrack(fileId: String)

    @Query("SELECT bytes FROM artwork WHERE fileId = :fileId LIMIT 1")
    suspend fun artwork(fileId: String): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putArtwork(artwork: ArtworkEntity)

    @Query("DELETE FROM artwork WHERE fileId = :fileId")
    suspend fun deleteArtwork(fileId: String)

    @Query("SELECT * FROM playlists ORDER BY createdAtEpochMs DESC")
    suspend fun allPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun playlist(id: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("SELECT json FROM singletons WHERE key = :key LIMIT 1")
    suspend fun singleton(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSingleton(value: SingletonEntity)

    @Query("DELETE FROM cached_tracks") suspend fun clearTracks()
    @Query("DELETE FROM artwork") suspend fun clearArtwork()
    @Query("DELETE FROM playlists") suspend fun clearPlaylists()
    @Query("DELETE FROM singletons") suspend fun clearSingletons()
}

@Database(
    entities = [
        CachedTrackEntity::class,
        ArtworkEntity::class,
        PlaylistEntity::class,
        SingletonEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
}
