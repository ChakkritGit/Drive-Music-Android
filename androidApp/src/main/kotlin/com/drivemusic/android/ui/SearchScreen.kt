package com.drivemusic.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.drivemusic.android.R
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.PlaySource

/**
 * Search, as its own screen rather than as a field wedged above Home's shelves.
 *
 * Searching is a mode, not an ornament: while it is happening the shelves are irrelevant and the
 * results want the whole screen. The field itself is the top bar's title, in the slot Home's search
 * button sits in — so the button does not move when it is pressed, it just becomes typable — and
 * this screen is nothing but results.
 */
@Composable
fun SearchScreen(
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    query: String,
    onAddToPlaylist: (DriveFile) -> Unit,
) {
    val normalized = query.trim().lowercase()
    val matches = remember(state.cachedTracks, normalized) {
        if (normalized.isEmpty()) emptyList()
        else state.cachedTracks.filter { track ->
            listOfNotNull(
                track.driveMeta.name, track.parsedMeta.title,
                track.parsedMeta.artist, track.parsedMeta.album,
            ).any { it.lowercase().contains(normalized) }
        }
    }

    // The queue is the whole downloaded set, not the matches — playing a result should carry on
    // into the rest of the library rather than stopping at the end of a transient filter.
    val queue = remember(state.cachedTracks) { state.cachedTracks.map { it.driveMeta } }
    val source = remember { PlaySource("__library__", "", PlaySource.Kind.LIBRARY) }

    Column(modifier = Modifier.fillMaxSize()) {
        when {
            normalized.isEmpty() -> Unit
            matches.isEmpty() -> Text(
                stringResource(R.string.no_tracks_match, query),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(matches, key = { it.fileId }) { track ->
                    TrackListRow(
                        file = track.driveMeta,
                        cachedTrack = track,
                        state = state,
                        viewModel = viewModel,
                        onClick = {
                            viewModel.play(queue, queue.indexOfFirst { it.id == track.fileId }, source)
                        },
                        onAddToPlaylist = { onAddToPlaylist(track.driveMeta) },
                    )
                    TrackDivider()
                }
            }
        }
    }
}
