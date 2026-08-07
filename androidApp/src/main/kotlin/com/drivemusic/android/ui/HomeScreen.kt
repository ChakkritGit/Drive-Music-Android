package com.drivemusic.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.shared.model.CachedTrack
import com.drivemusic.shared.model.PlaySource

/**
 * The landing screen: shelves over what is already downloaded.
 *
 * Everything here is built from the local library rather than from Drive, so it works offline and
 * costs no network. A library with nothing in it says so instead of showing empty shelves.
 */
@Composable
fun HomeScreen(state: PlayerViewModel.UiState, viewModel: PlayerViewModel) {
    val recommended = remember(state.cachedTracks) { viewModel.recommended() }
    val recentlyAdded = remember(state.cachedTracks) { state.cachedTracks.take(12) }

    if (state.cachedTracks.isEmpty() && state.playlists.isEmpty()) {
        EmptyState(
            title = "Nothing downloaded yet",
            message = "Browse your Drive and play something — it'll be saved for offline here.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item {
            Text(
                "Home",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        if (recentlyAdded.isNotEmpty()) {
            item {
                Shelf(
                    viewModel = viewModel,
                    title = "Recently added",
                    tracks = recentlyAdded,
                    onPlay = { index ->
                        viewModel.play(
                            recentlyAdded.map { it.driveMeta },
                            index,
                            PlaySource("recent", "Recently added", PlaySource.Kind.GENERATED),
                        )
                    },
                )
            }
        }

        if (recommended.isNotEmpty()) {
            item {
                Shelf(
                    viewModel = viewModel,
                    title = "Made for you",
                    subtitle = "Ranked by what you actually listen to",
                    tracks = recommended,
                    onPlay = { index ->
                        viewModel.play(
                            recommended.map { it.driveMeta },
                            index,
                            PlaySource("foryou", "Made for you", PlaySource.Kind.GENERATED),
                        )
                    },
                )
            }
        }

        if (state.playlists.isNotEmpty()) {
            item {
                SectionHeader("Playlists")
            }
            items(state.playlists, key = { it.id }) { playlist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = playlist.tracks.isNotEmpty()) {
                            viewModel.play(
                                playlist.tracks, 0,
                                PlaySource(playlist.id, playlist.name, PlaySource.Kind.PLAYLIST),
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Default.PlaylistPlay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${playlist.tracks.size} tracks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        val artists = state.artists
        if (artists.isNotEmpty()) {
            item { SectionHeader("Artists") }
            items(artists, key = { it.first }) { (artist, tracks) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.play(
                                tracks.map { it.driveMeta }, 0,
                                PlaySource(artist, artist, PlaySource.Kind.GENERATED),
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ArtworkThumb(viewModel, tracks.first(), size = 44.dp)
                    Column {
                        Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${tracks.size} tracks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Shelf(
    viewModel: PlayerViewModel,
    title: String,
    tracks: List<CachedTrack>,
    subtitle: String? = null,
    onPlay: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        SectionHeader(title, subtitle)
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(tracks, key = { it.fileId }) { track ->
                val index = tracks.indexOfFirst { it.fileId == track.fileId }
                Column(
                    modifier = Modifier.width(132.dp).clickable { onPlay(index) },
                ) {
                    ArtworkThumb(viewModel, track, size = 132.dp)
                    Text(
                        track.displayTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        track.parsedMeta.artist ?: "Unknown artist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun EmptyState(title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
