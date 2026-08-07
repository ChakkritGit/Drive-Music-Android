package com.drivemusic.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import com.drivemusic.android.R
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.android.player.TrackSort
import com.drivemusic.shared.model.CachedTrack
import com.drivemusic.shared.model.PlaySource

/**
 * Everything downloaded, sortable, playable offline.
 *
 * The sort is applied once per change rather than inside the row builder. That distinction
 * mattered on iOS, where the equivalent list re-sorted the whole library *per row* — 15 visible
 * rows meant 15 full sorts of every cached track on every recomposition.
 */
@Composable
fun LibraryScreen(
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    query: String,
    onQueryChange: (String) -> Unit,
    onAddToPlaylist: (com.drivemusic.shared.model.DriveFile) -> Unit,
) {
    var sort by remember { mutableStateOf(TrackSort.RECENTLY_ADDED) }

    val matching = remember(state.cachedTracks, query) {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) state.cachedTracks
        else state.cachedTracks.filter { track ->
            listOfNotNull(track.displayTitle, track.parsedMeta.artist, track.parsedMeta.album)
                .any { it.lowercase().contains(normalized) }
        }
    }

    val sorted = remember(matching, sort) {
        when (sort) {
            TrackSort.NAME -> matching.sortedBy { it.displayTitle.lowercase() }
            TrackSort.RECENTLY_ADDED -> matching.sortedByDescending { it.cachedAt }
            TrackSort.ARTIST -> matching.sortedBy { (it.parsedMeta.artist ?: "￿").lowercase() }
            TrackSort.ALBUM -> matching.sortedBy { (it.parsedMeta.album ?: "￿").lowercase() }
        }
    }
    val queue = remember(sorted) { sorted.map { it.driveMeta } }
    val source = remember { PlaySource("library", "Library", PlaySource.Kind.LIBRARY) }

    if (state.cachedTracks.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchField(query, stringResource(R.string.search_downloaded_tracks), onQueryChange)
            EmptyState(
                title = stringResource(R.string.library_empty),
                message = stringResource(R.string.library_empty_detail),
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        // Everything above the tracks is part of the scroll, so the search field and the header
        // move out of the way as you go down the list rather than holding a fixed slice of the
        // screen forever.
        item { SearchField(query, stringResource(R.string.search_downloaded_tracks), onQueryChange) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.downloaded_available_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                SortMenu(sort) { sort = it }
            }
        }
        item {
            Text(
                "${stringResource(R.string.track_count, sorted.size)} · ${formatBytes(state.cacheBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            CollectionActions(
                onPlay = { viewModel.play(queue, 0, source) },
                onShuffle = { viewModel.shufflePlay(queue, source) },
            )
        }
        if (sorted.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_downloaded_tracks_match, query),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(sorted, key = { it.fileId }) { track ->
                CachedTrackRow(
                    viewModel = viewModel,
                    track = track,
                    isPlaying = state.currentTrack?.id == track.fileId,
                    onClick = {
                        viewModel.play(queue, queue.indexOfFirst { it.id == track.fileId }, source)
                    },
                    onAddToPlaylist = { onAddToPlaylist(track.driveMeta) },
                    onQueue = { viewModel.addToQueue(track.driveMeta) },
                    onRemoveDownload = { viewModel.removeDownload(track.fileId) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
fun SortMenu(current: TrackSort, onSelect: (TrackSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(painterResource(AppIcons.Sort), contentDescription = null)
            Text(stringResource(current.labelRes), modifier = Modifier.padding(start = 4.dp))
        }
        AppMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TrackSort.entries.forEach { option ->
                AppMenuItem(
                    label = stringResource(option.labelRes),
                    onClick = { onSelect(option); expanded = false },
                    icon = AppIcons.Check.takeIf { option == current },
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Cover art for one cached track, loaded by id.
 *
 * The bytes are fetched on demand rather than carried on the track: holding the whole library's
 * artwork so a list can draw a few dozen thumbnails is how the iOS app ended up with hundreds of
 * megabytes of JPEG in view state.
 */
@Composable
fun ArtworkThumb(viewModel: PlayerViewModel, track: CachedTrack, size: Dp) {
    val bytes by produceState<ByteArray?>(initialValue = null, track.fileId) {
        value = viewModel.artworkFor(track.fileId)
    }
    val bitmap = remember(bytes) {
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }

    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(painterResource(AppIcons.MusicNote),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(size / 2.5f),
            )
        }
    }
}

@Composable
fun CachedTrackRow(
    viewModel: PlayerViewModel,
    track: CachedTrack,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onQueue: (() -> Unit)? = null,
    onRemoveDownload: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtworkThumb(viewModel, track, size = 48.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.displayTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isPlaying) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOfNotNull(track.parsedMeta.artist, track.parsedMeta.album)
                    .joinToString(" · ").ifEmpty { stringResource(R.string.unknown_artist) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(painterResource(AppIcons.MoreVert), contentDescription = stringResource(R.string.more))
            }
            AppMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                onQueue?.let {
                    AppMenuItem(stringResource(R.string.play_next), { it(); menuOpen = false }, AppIcons.QueuePlayNext)
                }
                onAddToPlaylist?.let {
                    AppMenuItem(stringResource(R.string.add_to_playlist), { it(); menuOpen = false }, AppIcons.PlaylistAdd)
                }
                onRemoveDownload?.let {
                    AppMenuItem(
                        stringResource(R.string.remove_download),
                        { it(); menuOpen = false },
                        AppIcons.Delete,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
