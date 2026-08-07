package com.drivemusic.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.res.painterResource
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
    onQueryChange: (String) -> Unit,
    onOpen: (Collection) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf<Playlist?>(null) }

    val visible = remember(state.playlists, query) {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) state.playlists
        else state.playlists.filter { it.name.lowercase().contains(normalized) }
    }

    // Deleting a playlist cannot be undone and the row it belongs to is one people tap all day, so
    // it asks. The name is in the question: "are you sure" tells the reader nothing they can check.
    confirmingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text(stringResource(R.string.delete_playlist)) },
            text = { Text(stringResource(R.string.delete_playlist_confirm, target.name)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.deletePlaylist(target.id); confirmingDelete = null },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.create_playlist),
            confirmLabel = stringResource(R.string.save),
            onDismiss = { creating = false },
        ) { name ->
            viewModel.createPlaylist(name)
            creating = false
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        // Search and the create row are part of the scroll, so they move out of the way rather
        // than holding a fixed slice of the screen.
        item { SearchField(query, stringResource(R.string.search_playlists), onChange = onQueryChange) }
        item {
            // A button, not a permanently open text field. Creating a playlist happens once in a
            // while; the field sat there empty the rest of the time, taking a row of a list whose
            // job is to show playlists, and it put a second text field directly under the search
            // one — two adjacent boxes that look alike and do entirely different things.
            // Sized to its label and pushed to the trailing edge: a full-width bar reads as the
            // primary thing on the screen, and the primary thing here is the list of playlists.
            // Filled rather than outlined because it is still the only action the screen offers.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = { creating = true },
                    // Tighter than the stock 24dp: that padding is sized for a button standing
                    // alone, and this one carries an icon as well as its label.
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(painterResource(AppIcons.Add), contentDescription = null)
                    Text(
                        stringResource(R.string.create_playlist),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        if (state.playlists.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_playlists_yet_create_one),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else if (visible.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_playlists_match, query),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
                items(visible, key = { it.id }) { playlist ->
                    val subtitle = trackCount(playlist.tracks.size)
                    SwipeToDeleteRow(onDeleteRequested = { confirmingDelete = playlist }) {
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
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
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
    }
}

/**
 * Asks for one name.
 *
 * The field takes focus as the dialog opens: a dialog whose entire content is a single text field
 * has nothing else the opening tap could have meant, and making the reader tap again to start
 * typing is a step that never had a purpose.
 */
@Composable
fun NameDialog(
    title: String,
    confirmLabel: String = stringResource(R.string.create),
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.name_field)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                // Enter confirms, so the keyboard's own action key does what the dialog's button
                // does rather than being a dead end.
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if (name.isNotBlank()) onConfirm(name.trim()) },
                ),
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(confirmLabel)
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
    var confirmingDelete by remember { mutableStateOf<Playlist?>(null) }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.new_playlist),
            onDismiss = { creating = false },
        ) { onCreate(it); creating = false; onDismiss() }
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
