package com.drivemusic.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivemusic.android.R
import com.drivemusic.shared.drive.DriveApiClient
import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.PlaySource

/** One level of the folder stack, so Back returns to where the user came from. */
data class Crumb(val id: String, val name: String)

/**
 * The Drive folder browser.
 *
 * Folders and audio files only — that filtering happens server-side in the query, so a Drive full
 * of documents and photos costs nothing to browse past.
 */
@Composable
fun BrowseScreen(
    drive: DriveApiClient,
    downloadedIds: Set<String>,
    onPlay: (List<DriveFile>, Int, PlaySource) -> Unit,
    onShuffle: (List<DriveFile>, PlaySource) -> Unit,
    onQueue: (DriveFile) -> Unit,
    onAddToPlaylist: (DriveFile) -> Unit,
    onDownloadAll: (List<DriveFile>) -> Unit,
    downloadProgress: Pair<Int, Int>?,
) {
    var stack by remember { mutableStateOf(listOf(Crumb("root", ""))) }
    var items by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val rootName = stringResource(R.string.my_drive)
    val current = stack.last().let { if (it.name.isEmpty()) it.copy(name = rootName) else it }

    LaunchedEffect(current.id) {
        isLoading = true
        error = null
        runCatching { drive.listFolder(current.id) }
            .onSuccess { items = it }
            .onFailure { error = it.message ?: "" }
        isLoading = false
    }

    val audioFiles = remember(items) { items.filterNot { it.isFolder } }
    val source = remember(current) { PlaySource(current.id, current.name, PlaySource.Kind.FOLDER) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (stack.size > 1) {
                IconButton(onClick = { stack = stack.dropLast(1) }) {
                    Icon(painterResource(AppIcons.ArrowBack), contentDescription = stringResource(R.string.back))
                }
            }
            Text(
                current.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        }

        if (audioFiles.isNotEmpty()) {
            CollectionActions(
                onPlay = { onPlay(audioFiles, 0, source) },
                onShuffle = { onShuffle(audioFiles, source) },
            )
            // The button reports what it is doing. Without this, tapping it looked like nothing
            // happened at all — the work is entirely off screen until a track's "Downloaded"
            // label appears minutes later.
            val remaining = audioFiles.count { it.id !in downloadedIds }
            androidx.compose.material3.TextButton(
                onClick = { onDownloadAll(audioFiles) },
                enabled = downloadProgress == null && remaining > 0,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                if (downloadProgress != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        stringResource(R.string.downloading_lld_lld, downloadProgress.first, downloadProgress.second),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    Icon(painterResource(AppIcons.Download), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        if (remaining == 0) stringResource(R.string.all_downloaded)
                        else stringResource(R.string.download_all_lld, remaining),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (downloadProgress != null) {
                LinearProgressIndicator(
                    progress = { downloadProgress.first.toFloat() / downloadProgress.second },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    error!!.ifEmpty { stringResource(R.string.could_not_load_folder) },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(stringResource(R.string.nothing_playable_here))
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { file ->
                    if (file.isFolder) {
                        FolderRow(file) { stack = stack + Crumb(file.id, file.displayName) }
                    } else {
                        BrowseTrackRow(
                            file = file,
                            isDownloaded = file.id in downloadedIds,
                            onClick = { onPlay(audioFiles, audioFiles.indexOfFirst { it.id == file.id }, source) },
                            onQueue = { onQueue(file) },
                            onAddToPlaylist = { onAddToPlaylist(file) },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FolderRow(file: DriveFile, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(AppIcons.Folder), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TrackRow(
    file: DriveFile,
    isDownloaded: Boolean,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    onQueue: (() -> Unit)? = null,
    @androidx.annotation.DrawableRes queueIcon: Int = AppIcons.Add,
    queueLabel: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(AppIcons.MusicNote),
            contentDescription = null,
            tint = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Gray,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (isDownloaded) {
                Text(
                    stringResource(R.string.downloaded),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        if (onQueue != null) {
            IconButton(onClick = onQueue) {
                Icon(painterResource(queueIcon), contentDescription = queueLabel ?: stringResource(R.string.play_next))
            }
        }
    }
}

/** A browse row with the overflow menu the library rows have. */
@Composable
private fun BrowseTrackRow(
    file: DriveFile,
    isDownloaded: Boolean,
    onClick: () -> Unit,
    onQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(AppIcons.MusicNote),
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isDownloaded) {
                Text(
                    stringResource(R.string.downloaded),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(painterResource(AppIcons.MoreVert), contentDescription = stringResource(R.string.more))
            }
            AppMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                AppMenuItem(stringResource(R.string.play_next), { onQueue(); menuOpen = false }, AppIcons.QueuePlayNext)
                AppMenuItem(
                    stringResource(R.string.add_to_playlist),
                    { onAddToPlaylist(); menuOpen = false },
                    AppIcons.PlaylistAdd,
                )
            }
        }
    }
}
