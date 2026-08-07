package com.drivemusic.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.PlaySource
import com.drivemusic.shared.model.Playlist

@Composable
fun PlaylistsScreen(state: PlayerViewModel.UiState, viewModel: PlayerViewModel) {
    var openPlaylist by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val selected = state.playlists.firstOrNull { it.id == openPlaylist }
    if (selected != null) {
        PlaylistDetail(selected, state, viewModel) { openPlaylist = null }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Playlists", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = "New playlist")
            }
        }

        if (state.playlists.isEmpty()) {
            EmptyState(
                title = "No playlists yet",
                message = "Create one, then add tracks from your library or from Drive.",
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.playlists, key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name) },
                        supportingContent = { Text("${playlist.tracks.size} tracks") },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deletePlaylist(playlist.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        },
                        modifier = Modifier.clickable { openPlaylist = playlist.id },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New playlist",
            onDismiss = { creating = false },
            onConfirm = { viewModel.createPlaylist(it); creating = false },
        )
    }
}

@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    val source = remember(playlist.id) {
        PlaySource(playlist.id, playlist.name, PlaySource.Kind.PLAYLIST)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        }

        if (playlist.tracks.isEmpty()) {
            EmptyState("Empty playlist", "Add tracks from your library or while browsing Drive.")
            return
        }

        CollectionActions(
            onPlay = { viewModel.play(playlist.tracks, 0, source) },
            onShuffle = { viewModel.shufflePlay(playlist.tracks, source) },
        )
        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            TextButton(onClick = { viewModel.downloadAll(playlist.tracks) }) {
                Text("Download all")
            }
        }
        state.downloadProgress?.let { (done, total) ->
            Text(
                "Downloading $done of $total",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(playlist.tracks, key = { it.id }) { file ->
                TrackRow(
                    file = file,
                    isDownloaded = file.id in state.downloadedIds,
                    isPlaying = state.currentTrack?.id == file.id,
                    onClick = {
                        viewModel.play(
                            playlist.tracks,
                            playlist.tracks.indexOfFirst { it.id == file.id },
                            source,
                        )
                    },
                    onQueue = { viewModel.removeFromPlaylist(playlist.id, file.id) },
                    queueIcon = Icons.Default.Delete,
                    queueLabel = "Remove from playlist",
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
fun NameDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks which playlist a track goes into. Shown from the library and the browser. */
@Composable
fun AddToPlaylistDialog(
    file: DriveFile,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        NameDialog("New playlist", { creating = false }) { onCreate(it); creating = false; onDismiss() }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text("No playlists yet.")
                } else {
                    playlists.forEach { playlist ->
                        ListItem(
                            headlineContent = { Text(playlist.name) },
                            supportingContent = { Text("${playlist.tracks.size} tracks") },
                            modifier = Modifier.clickable { onPick(playlist.id); onDismiss() },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { creating = true }) { Text("New playlist") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
