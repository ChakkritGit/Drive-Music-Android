package com.drivemusic.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivemusic.android.R
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.shared.model.CachedTrack
import com.drivemusic.shared.model.DriveFile

/**
 * One track in a list: artwork, title, artist · album, duration, an offline mark, and an overflow
 * menu. The row used everywhere a list of tracks appears, so a track looks the same wherever it
 * is met.
 *
 * Three things this replaces, all of which were wrong before:
 *
 * The artwork was a music-note glyph. Rows had covers available — the tracks are downloaded and
 * their art is in the database — and showed none of it, which made every list look the same.
 *
 * "Downloaded" was written out underneath the title, in the place a subtitle belongs. That is the
 * line that should carry the artist, and a word repeated down the whole list carries almost no
 * information: an icon at the trailing edge says the same thing without taking the subtitle's
 * place.
 *
 * There was no way to change whether a track was a favourite from a list at all. Favouriting lives
 * in the overflow menu and is deliberately not marked on the row: a heart repeated down a list of
 * mostly-favourites is the same non-information "Downloaded" was, and the trailing edge is narrow.
 */
@Composable
fun TrackListRow(
    file: DriveFile,
    cachedTrack: CachedTrack?,
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Track identity only: the same song shown in two playlists is genuinely the one playing in
    // both, and marking it in only one of them would be wrong.
    val isCurrent = state.currentTrack?.id == file.id
    val isFavorite = state.isFavorite(file.id)
    val isDownloaded = file.id in state.downloadedIds

    val title = cachedTrack?.parsedMeta?.title?.takeIf { it.isNotBlank() } ?: file.displayName
    val subtitle = listOfNotNull(cachedTrack?.parsedMeta?.artist, cachedTrack?.parsedMeta?.album)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowArtwork(file.id, viewModel, isCurrent, state.isPlaying, state.isLoading)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                // The one place this otherwise-monochrome list uses colour: the track playing.
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        cachedTrack?.parsedMeta?.durationSec?.takeIf { it > 0 }?.let {
            Text(
                formatDuration((it * 1000).toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (isDownloaded) {
            Icon(
                painterResource(AppIcons.CloudDone),
                contentDescription = stringResource(R.string.downloaded),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(painterResource(AppIcons.MoreVert), contentDescription = stringResource(R.string.more))
            }
            AppMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                AppMenuItem(
                    label = stringResource(
                        if (isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites
                    ),
                    onClick = { viewModel.toggleFavorite(file); menuOpen = false },
                    icon = if (isFavorite) AppIcons.HeartBroken else AppIcons.Favorite,
                )
                AppMenuItem(
                    label = stringResource(R.string.add_to_queue),
                    onClick = { viewModel.addToQueue(file); menuOpen = false },
                    icon = AppIcons.QueuePlayNext,
                )
                AppMenuItem(
                    label = stringResource(R.string.add_to_playlist),
                    onClick = { onAddToPlaylist(); menuOpen = false },
                    icon = AppIcons.PlaylistAdd,
                )
                onRemove?.let {
                    AppMenuItem(
                        label = stringResource(R.string.remove),
                        onClick = { it(); menuOpen = false },
                        icon = AppIcons.Delete,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * The row's cover, with the playing state drawn over it.
 *
 * A scrim under the indicator rather than the indicator alone: cover art is arbitrary, and a
 * light bar on a light cover is invisible.
 */
@Composable
private fun RowArtwork(
    fileId: String,
    viewModel: PlayerViewModel,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
) {
    val bytes by produceState<ByteArray?>(initialValue = null, fileId) {
        value = viewModel.artworkFor(fileId)
    }
    val bitmap = remember(bytes) {
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }

    Box(
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
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
            Icon(
                painterResource(AppIcons.MusicNote),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }

        if (isCurrent) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Icon(
                    painterResource(if (isPlaying) AppIcons.Equalizer else AppIcons.Pause),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
