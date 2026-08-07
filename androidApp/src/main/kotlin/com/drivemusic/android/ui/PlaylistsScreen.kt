package com.drivemusic.android.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivemusic.android.R
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.PlaySource
import com.drivemusic.shared.model.Playlist

@Composable
fun PlaylistsScreen(
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    query: String,
    onOpen: (Collection) -> Unit,
) {
    var newName by remember { mutableStateOf("") }

    val visible = remember(state.playlists, query) {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) state.playlists
        else state.playlists.filter { it.name.lowercase().contains(normalized) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Inline creation rather than a dialog behind a "+" — matches iOS, and making a playlist
        // is the thing this screen is for.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = { Text(stringResource(R.string.new_playlist_name)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { viewModel.createPlaylist(newName.trim()); newName = "" },
                enabled = newName.isNotBlank(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.create))
            }
        }

        if (state.playlists.isEmpty()) {
            EmptyState(
                stringResource(R.string.no_playlists_yet),
                stringResource(R.string.no_playlists_detail),
            )
        } else if (visible.isEmpty()) {
            EmptyState(stringResource(R.string.no_matches), stringResource(R.string.no_playlists_match, query))
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(visible, key = { it.id }) { playlist ->
                    val subtitle = trackCount(playlist.tracks.size)
                    PlaylistRow(
                        viewModel = viewModel,
                        playlist = playlist,
                        onOpen = {
                            onOpen(
                                Collection(
                                    playlist.name,
                                    subtitle,
                                    playlist.tracks,
                                    PlaySource(playlist.id, playlist.name, PlaySource.Kind.PLAYLIST),
                                )
                            )
                        },
                        onDelete = { viewModel.deletePlaylist(playlist.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

/** A playlist row with the same 2×2 collage the Home cards use, at row scale. */
@Composable
private fun PlaylistRow(
    viewModel: PlayerViewModel,
    playlist: Playlist,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val covers by androidx.compose.runtime.produceState(
        initialValue = emptyList<ByteArray>(),
        playlist.id,
        playlist.tracks.size,
    ) { value = viewModel.coversFor(playlist.tracks) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtworkCollage(covers, size = 52.dp, cornerRadius = 8.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                trackCount(playlist.tracks.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_playlist))
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
                label = { Text(stringResource(R.string.name_field)) },
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
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
        NameDialog(stringResource(R.string.new_playlist), { creating = false }) { onCreate(it); creating = false; onDismiss() }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_playlist)) },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text(stringResource(R.string.no_playlists_yet))
                } else {
                    playlists.forEach { playlist ->
                        ListItem(
                            headlineContent = { Text(playlist.name) },
                            supportingContent = { Text(trackCount(playlist.tracks.size)) },
                            modifier = Modifier.clickable { onPick(playlist.id); onDismiss() },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { creating = true }) { Text(stringResource(R.string.new_playlist)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
