package com.drivemusic.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivemusic.android.R
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.shared.model.DriveFile

/**
 * A collection opened from a Home card or a playlist: Play / Shuffle, Download all, search, then
 * every track. Mirrors the iOS `CollectionDetailView`, which every shelf card also opens into.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(
    collection: Collection,
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    onAddToPlaylist: (DriveFile) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val visible = remember(collection.tracks, query, state.cachedTracks) {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) collection.tracks
        else collection.tracks.filter { file ->
            val meta = state.cachedTracks.firstOrNull { it.fileId == file.id }?.parsedMeta
            listOfNotNull(file.name, meta?.title, meta?.artist, meta?.album)
                .any { it.lowercase().contains(normalized) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            collection.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

            if (collection.tracks.isEmpty()) {
                EmptyState(stringResource(R.string.nothing_here), stringResource(R.string.collection_empty))
                return@Column
            }

            CollectionActions(
                onPlay = { viewModel.play(collection.tracks, 0, collection.source) },
                onShuffle = { viewModel.shufflePlay(collection.tracks, collection.source) },
            )
            val remaining = collection.tracks.count { it.id !in state.downloadedIds }
            val progress = state.downloadProgress
            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                TextButton(
                    onClick = { viewModel.downloadAll(collection.tracks) },
                    enabled = progress == null && remaining > 0,
                ) {
                    if (progress != null) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            stringResource(R.string.downloading_lld_lld, progress.first, progress.second),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    } else {
                        Text(
                            if (remaining == 0) stringResource(R.string.all_downloaded)
                            else stringResource(R.string.download_all_lld, remaining)
                        )
                    }
                }
            }
            if (progress != null) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress.first.toFloat() / progress.second },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            SearchField(query, stringResource(R.string.search_this_collection)) { query = it }

            LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(visible, key = { it.id }) { file ->
                    TrackRow(
                        file = file,
                        isDownloaded = file.id in state.downloadedIds,
                        isPlaying = state.currentTrack?.id == file.id,
                        onClick = {
                            viewModel.play(
                                collection.tracks,
                                collection.tracks.indexOfFirst { it.id == file.id },
                                collection.source,
                            )
                        },
                        onQueue = { onAddToPlaylist(file) },
                        queueIcon = Icons.Default.PlaylistAdd,
                        queueLabel = stringResource(R.string.add_to_playlist),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
