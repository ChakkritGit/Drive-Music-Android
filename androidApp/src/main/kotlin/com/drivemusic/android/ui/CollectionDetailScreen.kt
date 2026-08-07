package com.drivemusic.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.drivemusic.android.R
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.shared.model.DriveFile

/**
 * A collection opened from a Home card or a playlist: Play / Shuffle, download-all, search, then
 * every track. Mirrors the iOS `CollectionDetailView`.
 *
 * The subtitle is deliberately not repeated in the body. It exists to tell the *card* apart from
 * its neighbours on a shelf; once opened, the title in the bar has already said what this is, and
 * a second line saying "Playlist" underneath is noise.
 */
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
    val cachedById = remember(state.cachedTracks) { state.cachedById }
    val remaining = collection.tracks.count { it.id !in state.downloadedIds }
    val progress = state.downloadProgress

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (collection.tracks.isNotEmpty()) {
            item {
                CollectionActions(
                    onPlay = { viewModel.play(collection.tracks, 0, collection.source) },
                    onShuffle = { viewModel.shufflePlay(collection.tracks, collection.source) },
                )
            }
            item { DownloadAllRow(remaining, progress) { viewModel.downloadAll(collection.tracks) } }
            // The header is three separate statements — what you can do with this collection,
            // what state its files are in, and how to narrow it — and with no space between them
            // they read as one block of clutter above the tracks.
            item {
                SearchBar(
                    query,
                    stringResource(R.string.search_this_collection),
                    modifier = Modifier.padding(bottom = 12.dp),
                ) { query = it }
            }
        }

        when {
            collection.tracks.isEmpty() -> item {
                Note(stringResource(R.string.nothing_here_yet))
            }
            visible.isEmpty() -> item {
                Note(stringResource(R.string.no_tracks_match, query))
            }
            else -> items(visible, key = { it.id }) { file ->
                TrackListRow(
                    file = file,
                    cachedTrack = cachedById[file.id],
                    state = state,
                    viewModel = viewModel,
                    onClick = {
                        viewModel.play(
                            collection.tracks,
                            collection.tracks.indexOfFirst { it.id == file.id },
                            collection.source,
                        )
                    },
                    onAddToPlaylist = { onAddToPlaylist(file) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

/**
 * Reports what downloading this collection would do, and then what it is doing.
 *
 * Three states rather than one button, matching iOS: nothing to fetch says so, a run in progress
 * shows its count, and otherwise the button names how many are missing — so tapping it is a
 * decision rather than a guess.
 */
@Composable
private fun DownloadAllRow(remaining: Int, progress: Pair<Int, Int>?, onDownload: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            progress != null -> {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.downloading_lld_lld, progress.first, progress.second),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            remaining == 0 -> {
                Icon(
                    painterResource(AppIcons.Check),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.all_available_offline),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> TextButton(onClick = onDownload, contentPadding = PaddingValues(0.dp)) {
                Icon(
                    painterResource(AppIcons.Download),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    stringResource(R.string.download_all_lld, remaining),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(16.dp),
    )
}
