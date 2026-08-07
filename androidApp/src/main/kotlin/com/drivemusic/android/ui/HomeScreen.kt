package com.drivemusic.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.shared.model.CachedTrack
import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.PlaySource

/**
 * Shelves of collection cards, matching the iOS Home.
 *
 * Cards rather than track tiles, and a 2×2 collage rather than one cover: a folder or playlist has
 * no artwork of its own, and showing the first track's would read as "this *is* that track".
 * Tapping opens a [CollectionDetailScreen] rather than playing immediately — the same relationship
 * the iOS `NavigationLink` has, and it means a card can be inspected without committing to it.
 */
@Composable
fun HomeScreen(
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    query: String,
    onOpenCollection: (Collection) -> Unit,
) {
    if (query.isNotBlank()) {
        SearchResults(state, viewModel, query)
        return
    }

    val hasAnything = state.cachedTracks.isNotEmpty() || state.playlists.isNotEmpty() ||
        state.recentSources.isNotEmpty()

    if (!hasAnything) {
        EmptyState(
            title = "Nothing to show yet",
            message = "Head to Browse to play something from your Drive.",
        )
        return
    }

    val shuffleAll = remember(state.cachedTracks) { state.cachedTracks.map { it.driveMeta } }
    val recentlyAdded = remember(state.cachedTracks) {
        state.cachedTracks.sortedByDescending { it.cachedAt }.map { it.driveMeta }
    }
    val madeForYou = remember(state.cachedTracks) { viewModel.recommended(20).map { it.driveMeta } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (state.recentSources.isNotEmpty()) {
            item {
                Shelf("Recently played", Icons.Default.Schedule) {
                    items(state.recentSources, key = { it.source.id }) { recent ->
                        CollectionCardFor(
                            viewModel = viewModel,
                            title = recent.source.name,
                            subtitle = sourceLabel(recent.source.kind),
                            tracks = recent.tracks,
                            onClick = {
                                onOpenCollection(
                                    Collection(recent.source.name, sourceLabel(recent.source.kind), recent.tracks, recent.source)
                                )
                            },
                        )
                    }
                }
            }
        }

        if (state.playlists.isNotEmpty()) {
            item {
                Shelf("Your playlists", null) {
                    items(state.playlists, key = { it.id }) { playlist ->
                        CollectionCardFor(
                            viewModel = viewModel,
                            title = playlist.name,
                            subtitle = trackCount(playlist.tracks.size),
                            tracks = playlist.tracks,
                            fallbackIcon = Icons.Default.Favorite,
                            onClick = {
                                onOpenCollection(
                                    Collection(
                                        playlist.name, "Playlist", playlist.tracks,
                                        PlaySource(playlist.id, playlist.name, PlaySource.Kind.PLAYLIST),
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }

        if (state.cachedTracks.isNotEmpty()) {
            item {
                Shelf("Recommended for you", Icons.Default.AutoAwesome) {
                    item {
                        CollectionCardFor(
                            viewModel, "Shuffle All", trackCount(shuffleAll.size) + " downloaded", shuffleAll,
                            onClick = { onOpenCollection(Collection("Shuffle All", "All downloaded tracks", shuffleAll, null)) },
                        )
                    }
                    item {
                        CollectionCardFor(
                            viewModel, "Recently Added", "From your downloads", recentlyAdded,
                            onClick = { onOpenCollection(Collection("Recently Added", "From your downloads", recentlyAdded, null)) },
                        )
                    }
                    item {
                        CollectionCardFor(
                            viewModel, "Made For You", "Picked from what you play", madeForYou,
                            onClick = { onOpenCollection(Collection("Made For You", "Picked from what you play", madeForYou, null)) },
                        )
                    }
                }
            }
        }

        val artists = state.artists
        if (artists.isNotEmpty()) {
            item {
                Shelf("Artists", Icons.Default.Mic) {
                    items(artists, key = { it.first }) { (artist, tracks) ->
                        val files = tracks.map { it.driveMeta }
                        CollectionCardFor(
                            viewModel = viewModel,
                            title = artist,
                            subtitle = trackCount(tracks.size),
                            tracks = files,
                            fallbackIcon = Icons.Default.Mic,
                            onClick = { onOpenCollection(Collection(artist, "Artist", files, null)) },
                        )
                    }
                }
            }
        }
    }
}

/** What a card opens: a named set of tracks, optionally attributable to a source. */
data class Collection(
    val title: String,
    val subtitle: String,
    val tracks: List<DriveFile>,
    val source: PlaySource?,
)

@Composable
private fun Shelf(
    title: String,
    icon: ImageVector?,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
            }
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.outline)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A card whose covers are fetched by id, four at most — see `PlayerViewModel.coversFor`. */
@Composable
fun CollectionCardFor(
    viewModel: PlayerViewModel,
    title: String,
    subtitle: String,
    tracks: List<DriveFile>,
    fallbackIcon: ImageVector = Icons.Default.MusicNote,
    onClick: () -> Unit,
) {
    val covers by produceState(initialValue = emptyList<ByteArray>(), tracks.take(4).map { it.id }) {
        value = viewModel.coversFor(tracks)
    }
    CollectionCard(title, subtitle, covers, fallbackIcon, onClick)
}

@Composable
private fun SearchResults(state: PlayerViewModel.UiState, viewModel: PlayerViewModel, query: String) {
    val normalized = query.trim().lowercase()
    val matches = remember(state.cachedTracks, normalized) {
        state.cachedTracks.filter { track ->
            listOfNotNull(
                track.displayTitle, track.parsedMeta.artist, track.parsedMeta.album,
            ).any { it.lowercase().contains(normalized) }
        }
    }

    if (matches.isEmpty()) {
        EmptyState("No matches", "Nothing downloaded matches \"$query\".")
        return
    }

    val queue = remember(matches) { matches.map { it.driveMeta } }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(matches, key = { it.fileId }) { track ->
            CachedTrackRow(
                viewModel = viewModel,
                track = track,
                isPlaying = state.currentTrack?.id == track.fileId,
                onClick = { viewModel.play(queue, queue.indexOfFirst { it.id == track.fileId }, null) },
                onQueue = { viewModel.addToQueue(track.driveMeta) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

private fun sourceLabel(kind: PlaySource.Kind) = when (kind) {
    PlaySource.Kind.PLAYLIST -> "Playlist"
    PlaySource.Kind.LIBRARY -> "Library"
    PlaySource.Kind.FOLDER -> "Folder"
    PlaySource.Kind.GENERATED -> "Mix"
}

fun trackCount(count: Int) = "$count track" + if (count == 1) "" else "s"

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
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
        )
    }
}
