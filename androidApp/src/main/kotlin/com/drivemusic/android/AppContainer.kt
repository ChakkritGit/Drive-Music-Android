package com.drivemusic.android

import android.content.Context
import androidx.room.Room
import com.drivemusic.android.auth.GoogleAuth
import com.drivemusic.android.data.FileAudioStore
import com.drivemusic.android.data.LibraryDatabase
import com.drivemusic.android.data.RoomTrackLibrary
import com.drivemusic.shared.drive.DriveApiClient
import com.drivemusic.shared.drive.driveHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * The object graph, assembled by hand.
 *
 * No DI framework: there are six things here and one place that needs them. A framework would add
 * a build step and a layer of indirection to solve a problem this app does not have yet.
 */
class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext

    val auth = GoogleAuth(applicationContext)

    private val database = Room.databaseBuilder(
        applicationContext,
        LibraryDatabase::class.java,
        "drive-music.db",
    ).addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3).build()

    val library = RoomTrackLibrary(database.dao())
    val files = FileAudioStore(applicationContext)

    val drive = DriveApiClient(
        tokens = auth,
        http = driveHttpClient(HttpClient(OkHttp)),
    )

    /**
     * Drops every cached remote image.
     *
     * Called on sign-out: the profile picture and any Drive thumbnails were fetched with the
     * previous account's token and belong to its files. Downloads are left alone — those are the
     * user's library, not a cache.
     */
    fun clearImageCaches() {
        val loader = coil.Coil.imageLoader(applicationContext)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
