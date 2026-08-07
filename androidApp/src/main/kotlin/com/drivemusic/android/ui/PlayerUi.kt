package com.drivemusic.android.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import com.drivemusic.android.R
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.LoopMode

@Composable
fun CollectionActions(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onPlay) {
            Icon(painterResource(AppIcons.PlayArrow), contentDescription = null)
            Text(stringResource(R.string.play), modifier = Modifier.padding(start = 4.dp))
        }
        OutlinedButton(onClick = onShuffle) {
            Icon(painterResource(AppIcons.Shuffle), contentDescription = null)
            Text(stringResource(R.string.shuffle), modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** The key both the mini player and Now Playing register their artwork under. */
private const val ARTWORK_KEY = "now-playing-artwork"

/**
 * The rest of the container transform.
 *
 * Sharing only the artwork made the cover fly while everything around it cross-faded, which reads
 * as two unrelated animations rather than as one surface growing. The title and artist follow the
 * cover, and the mini player's own surface morphs into the full screen's background so there is a
 * continuous container the whole way — that container is what makes the gesture feel like opening
 * the thing you touched instead of replacing it.
 */
private const val CONTAINER_KEY = "now-playing-container"
private const val TITLE_KEY = "now-playing-title"
private const val ARTIST_KEY = "now-playing-artist"

/**
 * Text scales between the two sizes rather than being re-laid-out at every frame: the title goes
 * from `bodyMedium` to `titleLarge`, and remeasuring type mid-flight produces reflow judder.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private val TEXT_RESIZE = SharedTransitionScope.ResizeMode.ScaleToBounds(
    contentScale = ContentScale.FillWidth,
    alignment = Alignment.CenterStart,
)

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
            Icon(painterResource(AppIcons.MusicNote),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayer(
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onExpand: () -> Unit,
) {
    val track = state.currentTrack ?: return

    Surface(
        tonalElevation = 3.dp,
        modifier = with(sharedScope) {
            Modifier.fillMaxWidth().sharedBounds(
                rememberSharedContentState(CONTAINER_KEY),
                animatedVisibilityScope = animatedScope,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            )
        },
    ) {
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
                with(sharedScope) {
                    Artwork(
                        state.artwork,
                        Modifier
                            .sharedElement(
                                rememberSharedContentState(ARTWORK_KEY),
                                animatedVisibilityScope = animatedScope,
                            )
                            .size(48.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    with(sharedScope) {
                        Text(
                            state.metadata?.title ?: track.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.sharedBounds(
                                rememberSharedContentState(TITLE_KEY),
                                animatedVisibilityScope = animatedScope,
                                resizeMode = TEXT_RESIZE,
                            ),
                        )
                        state.metadata?.artist?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.sharedBounds(
                                    rememberSharedContentState(ARTIST_KEY),
                                    animatedVisibilityScope = animatedScope,
                                    resizeMode = TEXT_RESIZE,
                                ),
                            )
                        }
                    }
                }
                IconButton(onClick = viewModel::togglePlayPause) {
                    Icon(
                        painterResource(if (state.isPlaying) AppIcons.Pause else AppIcons.PlayArrow),
                        contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                    )
                }
                IconButton(onClick = viewModel::next) {
                    Icon(painterResource(AppIcons.SkipNext), contentDescription = stringResource(R.string.next))
                }
            }
        }
    }
}

/**
 * The full-screen player.
 *
 * Laid out like the iOS version: a left-aligned title block with the favourite toggle beside it,
 * the source underneath, then the slider, then the transport with the play button as a filled
 * circle. Centring the title, which this did before, put the favourite button somewhere it had to
 * be hunted for and made the block read as a caption rather than as the thing the screen is about.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingScreen(
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onDismiss: () -> Unit,
) {
    val track = state.currentTrack ?: return
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var queueOpen by remember { mutableStateOf(false) }
    // Hosted here rather than in the shell: this screen is presented above it, so the shell's own
    // dialog host is not in scope while the queue is open.
    var addingToPlaylist by remember { mutableStateOf<DriveFile?>(null) }

    // The mini player's surface, grown to fill the screen. Sharing the container rather than
    // cross-fading the two backgrounds is what makes this read as one surface expanding: without
    // it the cover flies across a screen that is simultaneously dissolving underneath it.
    Surface(
        modifier = with(sharedScope) {
            Modifier.fillMaxSize().sharedBounds(
                rememberSharedContentState(CONTAINER_KEY),
                animatedVisibilityScope = animatedScope,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            )
        },
    ) {
    Column(
        // Full-screen and inside the shared container, so it pads itself. `safeDrawing` rather
        // than `statusBars`: it also covers the gesture bar and any display cutout.
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) {
                Icon(painterResource(AppIcons.KeyboardArrowDown), contentDescription = stringResource(R.string.close))
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.now_playing),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(modifier = Modifier.weight(1f))
            // Balances the close button so the title sits centred.
            Spacer(modifier = Modifier.size(48.dp))
        }

        with(sharedScope) {
            Artwork(
                state.artwork,
                Modifier
                    .sharedElement(
                        rememberSharedContentState(ARTWORK_KEY),
                        animatedVisibilityScope = animatedScope,
                    )
                    .fillMaxWidth(0.82f)
                    .aspectRatio(1f),
            )
        }

        // Left-aligned, with the favourite toggle on the title's own row.
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                with(sharedScope) {
                    Text(
                        state.metadata?.title ?: track.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        // Scrolls instead of truncating, like the iOS marquee. A title is the one
                        // string on this screen the reader may actually need in full, and
                        // "Gryffin & Illenium Ft. Daya - Feel…" withholds exactly the part that
                        // distinguishes one upload of a song from another.
                        modifier = Modifier
                            .weight(1f)
                            .basicMarquee(iterations = Int.MAX_VALUE)
                            .sharedBounds(
                                rememberSharedContentState(TITLE_KEY),
                                animatedVisibilityScope = animatedScope,
                                resizeMode = TEXT_RESIZE,
                            ),
                    )
                }
                IconButton(onClick = { viewModel.toggleFavorite(track) }) {
                    Icon(
                        painterResource(if (state.isCurrentFavorite) AppIcons.Favorite else AppIcons.FavoriteBorder),
                        contentDescription = stringResource(R.string.favourite),
                        tint = if (state.isCurrentFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    )
                }
            }

            if (state.error != null) {
                Text(state.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            } else {
                with(sharedScope) {
                    Text(
                        listOfNotNull(state.metadata?.artist, state.metadata?.album)
                            .joinToString(" · ").ifEmpty { " " },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.sharedBounds(
                            rememberSharedContentState(ARTIST_KEY),
                            animatedVisibilityScope = animatedScope,
                            resizeMode = TEXT_RESIZE,
                        ),
                    )
                }
            }

            state.source?.let {
                Text(
                    stringResource(R.string.playing_from, it.name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp)) {
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(state.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }

        TransportControls(state, viewModel)

        // Utility row: what is happening on one side, what you can do on the other — matching
        // iOS. The queue lives here rather than as a header action, and is disabled when there is
        // nothing after this track to look at.
        Row(
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.crossfadeEnabled && state.autoMixEnabled && state.isPlaying) {
                MixBadge()
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { queueOpen = true }, enabled = state.upNext.isNotEmpty()) {
                Icon(painterResource(AppIcons.List),
                    contentDescription = stringResource(R.string.queue),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
    }

    if (queueOpen) {
        QueueSheet(
            state = state,
            viewModel = viewModel,
            onAddToPlaylist = { addingToPlaylist = it },
            onDismiss = { queueOpen = false },
        )
    }

    addingToPlaylist?.let { file ->
        AddToPlaylistDialog(
            file = file,
            playlists = state.playlists,
            onDismiss = { addingToPlaylist = null },
            onPick = { viewModel.addToPlaylist(it, file) },
            onCreate = { viewModel.createPlaylist(it) },
        )
    }
}

/**
 * "Mixing", while the mix engine is shaping this transition.
 *
 * A label, not a control: the mix settings live in Settings, and a tappable-looking badge here
 * would say otherwise. It sits opposite the queue button so the row reads as "what is happening"
 * on one side and "what you can do" on the other.
 *
 * iOS also shows the detected tempo here. Android has no track analysis yet, so there is no BPM to
 * show and this says the honest thing rather than inventing one.
 */
@Composable
private fun MixBadge() {
    Row(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painterResource(AppIcons.AutoAwesome),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            stringResource(R.string.mixing),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Shuffle, previous, play, next, repeat.
 *
 * The play button is a filled circle at 72dp and the skip buttons are 52dp, matching iOS —
 * shuffle and repeat at 44dp are secondary to both, which is the ordering of importance they
 * actually have. All five clear the 44dp minimum touch target.
 */
@Composable
private fun TransportControls(state: PlayerViewModel.UiState, viewModel: PlayerViewModel) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = viewModel::toggleShuffle, modifier = Modifier.size(44.dp)) {
            Icon(painterResource(AppIcons.Shuffle),
                contentDescription = stringResource(R.string.shuffle),
                tint = if (state.queue.shuffle) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
            )
        }
        IconButton(
            onClick = viewModel::previous,
            enabled = state.currentTrack != null,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(painterResource(AppIcons.SkipPrevious), contentDescription = stringResource(R.string.prev), modifier = Modifier.size(34.dp))
        }

        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = viewModel::togglePlayPause,
                enabled = state.currentTrack != null && !state.isLoading,
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    painterResource(if (state.isPlaying) AppIcons.Pause else AppIcons.PlayArrow),
                    contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(38.dp),
                )
            }
        }

        IconButton(
            onClick = viewModel::next,
            enabled = state.currentTrack != null,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(painterResource(AppIcons.SkipNext), contentDescription = stringResource(R.string.next), modifier = Modifier.size(34.dp))
        }
        IconButton(onClick = viewModel::cycleLoopMode, modifier = Modifier.size(44.dp)) {
            Icon(
                painterResource(if (state.queue.loopMode == LoopMode.ONE) AppIcons.RepeatOne else AppIcons.Repeat),
                contentDescription = stringResource(R.string.repeat),
                tint = if (state.queue.loopMode == LoopMode.OFF) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The queue, matching the iOS sheet: the current track pinned at the top under a "Playing from"
 * header — it is not *upcoming*, so it is not removable — then the real upcoming order under
 * "Next up".
 *
 * These rows used to be `TrackRow`, the placeholder row every list has since stopped using: a
 * music-note glyph where the cover belongs and the word "Downloaded" where the artist belongs.
 * A queue of identical grey notes tells you nothing about what is coming, which is the only
 * question this sheet exists to answer.
 *
 * Tapping the current row toggles play/pause, so its trailing indicator is a control rather than
 * decoration. Up Next tracks are real files, so they carry the same menu they do everywhere else —
 * Play Next, favouriting, Add to Playlist — not just a bare Remove.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    onAddToPlaylist: (DriveFile) -> Unit,
    onDismiss: () -> Unit,
) {
    val cachedById = state.cachedById

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item {
                Text(
                    stringResource(R.string.queue),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            state.currentTrack?.let { current ->
                item {
                    Text(
                        state.source?.name?.takeIf { it.isNotBlank() }
                            ?.let { stringResource(R.string.playing_from, it) }
                            ?: stringResource(R.string.now_playing_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                item {
                    QueueRow(
                        file = current,
                        cachedTrack = cachedById[current.id],
                        viewModel = viewModel,
                        isCurrent = true,
                        // The whole row is the play/pause control, matching iOS — the indicator
                        // on its trailing edge is what you are aiming at, and a row-sized target
                        // is easier to hit than the glyph.
                        onClick = viewModel::togglePlayPause,
                        trailing = {
                            Icon(
                                painterResource(
                                    if (state.isPlaying) AppIcons.Equalizer else AppIcons.PlayArrow
                                ),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                    TrackDivider()
                }
            }

            if (state.upNext.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.nothing_queued),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                item {
                    Text(
                        stringResource(R.string.next_up),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(state.upNext, key = { it.index }) { entry ->
                    var menuOpen by remember { mutableStateOf(false) }
                    val isFavorite = state.isFavorite(entry.file.id)

                    QueueRow(
                        file = entry.file,
                        cachedTrack = cachedById[entry.file.id],
                        viewModel = viewModel,
                        isCurrent = false,
                        onClick = { viewModel.jumpTo(entry.index); onDismiss() },
                        trailing = {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(
                                        painterResource(AppIcons.MoreVert),
                                        contentDescription = stringResource(R.string.more),
                                    )
                                }
                                AppMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    AppMenuItem(
                                        label = stringResource(R.string.play_next),
                                        // `addToQueue` already moves an already-queued file to
                                        // just after the current one, so this is "jump to the
                                        // front of Up Next" wherever it started.
                                        onClick = { viewModel.addToQueue(entry.file); menuOpen = false },
                                        icon = AppIcons.QueuePlayNext,
                                    )
                                    AppMenuItem(
                                        label = stringResource(
                                            if (isFavorite) R.string.remove_from_favorites
                                            else R.string.add_to_favorites
                                        ),
                                        onClick = { viewModel.toggleFavorite(entry.file); menuOpen = false },
                                        icon = if (isFavorite) AppIcons.HeartBroken else AppIcons.Favorite,
                                    )
                                    AppMenuItem(
                                        label = stringResource(R.string.add_to_playlist),
                                        onClick = { onAddToPlaylist(entry.file); menuOpen = false },
                                        icon = AppIcons.PlaylistAdd,
                                    )
                                    AppMenuItem(
                                        label = stringResource(R.string.remove_from_queue),
                                        onClick = { viewModel.removeFromQueue(entry.index); menuOpen = false },
                                        icon = AppIcons.Close,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                    )
                    TrackDivider()
                }
            }
        }
    }
}

/** One queue row: cover, title, artist · album, and whatever control belongs on the trailing edge. */
@Composable
private fun QueueRow(
    file: DriveFile,
    cachedTrack: com.drivemusic.shared.model.CachedTrack?,
    viewModel: PlayerViewModel,
    isCurrent: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val bytes by androidx.compose.runtime.produceState<ByteArray?>(initialValue = null, file.id) {
        value = viewModel.artworkFor(file.id)
    }
    val bitmap = remember(bytes) {
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }
    val title = cachedTrack?.parsedMeta?.title?.takeIf { it.isNotBlank() } ?: file.displayName
    val subtitle = listOfNotNull(cachedTrack?.parsedMeta?.artist, cachedTrack?.parsedMeta?.album)
        .filter { it.isNotBlank() }
        .joinToString(" \u00b7 ")

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
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

        trailing()
    }
}
