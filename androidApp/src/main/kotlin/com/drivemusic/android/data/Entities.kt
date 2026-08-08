package com.drivemusic.android.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
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

/**
 * One track's analysis, in its own table.
 *
 * Separate from `cached_tracks` for the same reason artwork is: it is derived data with its own
 * lifecycle — recomputed when the analyser's version changes, absent until the analysis has run —
 * and folding it into the track row would mean rewriting every track row to store a BPM.
 *
 * The whole thing is stored as JSON rather than as columns. It is read as a unit, written as a
 * unit, and never queried by any of its fields, so columns would buy nothing and cost a migration
 * every time the analyser learns to measure something new.
 */
@Entity(tableName = "track_analysis")
data class TrackAnalysisEntity(
    @PrimaryKey val fileId: String,
    val json: String,
    /** Denormalised out of the JSON so a stale-version sweep does not have to parse every row. */
    val version: Int,
)

/**
 * One training step, kept so the analytics screen can show what the model predicted against what
 * actually happened.
 *
 * Its own table rather than a field on the model: the model is one row rewritten on every step,
 * and these are the history of those steps — folding them together would mean rewriting the whole
 * history to record one event.
 */
@Entity(tableName = "model_events")
data class ModelEventEntity(
    @PrimaryKey val id: String,
    val json: String,
    /** Denormalised so the list can be ordered without parsing every row. */
    val atMillis: Long,
)

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
    @Query("SELECT * FROM model_events ORDER BY atMillis DESC LIMIT :limit")
    suspend fun recentModelEvents(limit: Int): List<ModelEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putModelEvent(event: ModelEventEntity)

    /**
     * Keeps the newest [limit] and drops the rest.
     *
     * A training history that only grows is a table that only grows, and nothing reads past the
     * first page or two of it.
     */
    @Query("DELETE FROM model_events WHERE id NOT IN (SELECT id FROM model_events ORDER BY atMillis DESC LIMIT :limit)")
    suspend fun trimModelEvents(limit: Int)

    @Query("DELETE FROM model_events") suspend fun clearModelEvents()

    @Query("SELECT * FROM track_analysis")
    suspend fun allAnalyses(): List<TrackAnalysisEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAnalysis(analysis: TrackAnalysisEntity)

    @Query("DELETE FROM track_analysis WHERE version != :version")
    suspend fun deleteStaleAnalyses(version: Int)

    @Query("DELETE FROM track_analysis") suspend fun clearAnalyses()

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
        TrackAnalysisEntity::class,
        ModelEventEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        /**
         * Adds the analysis table.
         *
         * A real migration rather than `fallbackToDestructiveMigration`: analysis is derived and
         * could be thrown away safely, but destroying the database to add a table would take the
         * user's playlists and their whole downloaded library index with it.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `model_events` (" +
                        "`id` TEXT NOT NULL, " +
                        "`json` TEXT NOT NULL, " +
                        "`atMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `track_analysis` (" +
                        "`fileId` TEXT NOT NULL, " +
                        "`json` TEXT NOT NULL, " +
                        "`version` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`fileId`))"
                )
            }
        }
    }
}
