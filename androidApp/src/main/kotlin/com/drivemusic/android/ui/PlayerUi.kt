package com.drivemusic.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.shared.model.LoopMode

@Composable
fun CollectionActions(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onPlay) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Text("Play", modifier = Modifier.padding(start = 4.dp))
        }
        OutlinedButton(onClick = onShuffle) {
            Icon(Icons.Default.Shuffle, contentDescription = null)
            Text("Shuffle", modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** Decoded once per track rather than per recomposition — a cover is a megabyte of bitmap. */
@Composable
private fun Artwork(bytes: ByteArray?, modifier: Modifier = Modifier) {
    val bitmap = remember(bytes) {
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }
    Box(
        modifier = modifier.clip(RoundedCornerShape(12.dp))
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
                Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
fun MiniPlayer(state: PlayerViewModel.UiState, viewModel: PlayerViewModel, onExpand: () -> Unit) {
    val track = state.currentTrack ?: return

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        // No inset padding here: the mini player sits directly above the navigation bar, which
        // Material3 already insets itself. Padding both left a gap the height of the gesture bar
        // between them.
        Column {
            LinearProgressIndicator(
                progress = {
                    if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
                },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Artwork(state.artwork, Modifier.size(48.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.metadata?.title ?: track.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.metadata?.artist?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = viewModel::togglePlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = viewModel::next) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }
            }
        }
    }
}

@Composable
fun NowPlayingScreen(
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val track = state.currentTrack ?: return
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var queueOpen by remember { mutableStateOf(false) }

    Column(
        // Full-screen and outside the Scaffold, so it pads itself. `safeDrawing` rather than
        // `statusBars`: it also covers the gesture bar and any display cutout, which is what the
        // Up Next list at the bottom of this screen would otherwise run under.
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
            Box(modifier = Modifier.weight(1f)) {
                state.source?.let {
                    Text(
                        "Playing from ${it.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { queueOpen = true }) {
                Icon(Icons.Default.QueueMusic, contentDescription = "Queue")
            }
        }

        Artwork(state.artwork, Modifier.fillMaxWidth(0.8f).aspectRatio(1f))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                state.metadata?.title ?: track.displayName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(state.metadata?.artist, state.metadata?.album).joinToString(" · ")
                    .ifEmpty { "Unknown artist" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = scrubbing ?: if (state.durationMs > 0) {
                    state.positionMs.toFloat() / state.durationMs
                } else 0f,
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let { viewModel.seekTo((it * state.durationMs).toLong()) }
                    scrubbing = null
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatDuration(state.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = viewModel::toggleShuffle) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (state.queue.shuffle) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = viewModel::previous) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = viewModel::togglePlayPause) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(56.dp),
                )
            }
            IconButton(onClick = viewModel::next) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = viewModel::cycleLoopMode) {
                Icon(
                    if (state.queue.loopMode == LoopMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = "Repeat",
                    tint = if (state.queue.loopMode == LoopMode.OFF) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.primary,
                )
            }
        }

    }

    if (queueOpen) {
        QueueSheet(state, viewModel) { queueOpen = false }
    }
}

/**
 * The queue, as a sheet rather than a list under the transport.
 *
 * Inline it competed with the controls for vertical space, and on a short phone the transport was
 * the thing that lost — a player whose play button can be scrolled away is the wrong trade.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Next up",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.upNext.isEmpty()) {
                Text(
                    "Nothing queued after this track.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    itemsIndexed(state.upNext, key = { _, entry -> entry.index }) { _, entry ->
                        TrackRow(
                            file = entry.file,
                            isDownloaded = entry.file.id in state.downloadedIds,
                            onClick = { viewModel.jumpTo(entry.index); onDismiss() },
                            onQueue = { viewModel.removeFromQueue(entry.index) },
                            queueIcon = Icons.Default.Close,
                            queueLabel = "Remove from queue",
                        )
                    }
                }
            }
        }
    }
}
