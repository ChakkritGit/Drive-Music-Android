package com.drivemusic.android.data

import android.content.Context
import com.drivemusic.shared.data.AudioFileStore
import com.drivemusic.shared.data.AudioSink
import com.drivemusic.shared.model.DriveFile
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloaded audio on disk.
 *
 * Files live under the app's own files directory, named from the Drive file id. The id is
 * URL-safe by construction, but it is sanitised anyway — one unexpected character would otherwise
 * silently fail every write for that track, and a name that escaped the directory would be worse.
 */
class FileAudioStore(context: Context) : AudioFileStore {
    private val root = File(context.filesDir, "audio").apply { mkdirs() }

    private fun safeName(file: DriveFile): String {
        val extension = file.name.substringAfterLast('.', "").take(5)
            .filter { it.isLetterOrDigit() }
        val id = file.id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
        return if (extension.isEmpty()) id else "$id.$extension"
    }

    override suspend fun store(file: DriveFile, body: suspend (AudioSink) -> Unit): String =
        withContext(Dispatchers.IO) {
            val name = safeName(file)
            // Written to a temporary file and moved into place, so a download interrupted halfway
            // never leaves a truncated file that looks cached and plays as a fragment.
            val temporary = File(root, "$name.part")
            try {
                // Truncating, not appending: a retried transfer restarts from the first byte, and
                // appending would splice the second attempt onto the remains of the first.
                FileOutputStream(temporary, false).use { output ->
                    body { bytes, count -> output.write(bytes, 0, count) }
                    output.fd.sync()
                }
                val target = File(root, name)
                if (target.exists()) target.delete()
                temporary.renameTo(target)
            } catch (error: Throwable) {
                // A half-written `.part` left behind would be picked up as the destination of the
                // next attempt and, worse, counts toward the cache size the user is shown.
                temporary.delete()
                throw error
            }
            name
        }

    /**
     * The file itself, for the analyser.
     *
     * Not part of [AudioFileStore]: that interface is shared with iOS, where there is no
     * `java.io.File`, and the analyser is the only caller that needs a path rather than a URI.
     */
    fun file(relativePath: String): File = File(root, relativePath)

    override suspend fun uri(relativePath: String): String =
        File(root, relativePath).toURI().toString()

    override suspend fun delete(relativePath: String) = withContext(Dispatchers.IO) {
        File(root, relativePath).delete()
        Unit
    }

    override suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        root.listFiles()?.sumOf { it.length() } ?: 0L
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        root.listFiles()?.forEach { it.delete() }
        Unit
    }
}
