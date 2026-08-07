package com.drivemusic.android.data

import android.content.Context
import com.drivemusic.shared.data.AudioFileStore
import com.drivemusic.shared.model.DriveFile
import java.io.File
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

    override suspend fun store(data: ByteArray, file: DriveFile): String = withContext(Dispatchers.IO) {
        val name = safeName(file)
        // Written to a temporary file and moved into place, so a download interrupted halfway
        // never leaves a truncated file that looks cached and plays as a fragment.
        val temporary = File(root, "$name.part")
        temporary.writeBytes(data)
        val target = File(root, name)
        if (target.exists()) target.delete()
        temporary.renameTo(target)
        name
    }

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
