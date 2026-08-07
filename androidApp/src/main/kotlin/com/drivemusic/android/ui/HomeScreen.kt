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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drivemusic.android.R
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
    onOpenSearch: () -> Unit,
    onOpenCollection: (Collection) -> Unit,
) {

    val hasAnything = state.cachedTracks.isNotEmpty() || state.playlists.isNotEmpty() ||
        state.recentSources.isNotEmpty()

    if (!hasAnything) {
        Column(modifier = Modifier.fillMaxSize()) {
            GreetingHeader()
            EmptyState(
                message = stringResource(R.string.nothing_to_show_yet_head_to_browse_to_play_something_from_yo),
            )
        }
        return
    }

    val shuffleAll = remember(state.cachedTracks) { state.cachedTracks.map { it.driveMeta } }
    // Capped, like the other two: these are shelves to browse from, not complete indexes.
    val recentlyAdded = remember(state.cachedTracks) {
        state.cachedTracks.sortedByDescending { it.cachedAt }.take(SHELF_LIMIT).map { it.driveMeta }
    }
    // Keyed on the training count as well as the library, so the ranking re-reads once the model
    // has learned something new rather than staying frozen for the session.
    val madeForYou = remember(state.cachedTracks, state.trainingEvents) {
        viewModel.recommended(SHELF_LIMIT).map { it.driveMeta }
    }
    // Favourites first — it is the playlist most likely to be wanted, and it is an ordinary
    // playlist under the hood so nothing else distinguishes it.
    val playlists = remember(state.playlists) {
        state.playlists.sortedByDescending { it.name == PlayerViewModel.FAVORITES }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // The greeting, which used to be the top bar's title. It has traded places with the
        // search button: a greeting is a nicety that has earned no permanent slice of the screen,
        // so it scrolls away with the shelves, while search — wanted at any point, from any scroll
        // position — took the pinned slot it vacated.
        item { GreetingHeader() }

        if (state.recentSources.isNotEmpty()) {
            item {
                Shelf(stringResource(R.string.recently_played), AppIcons.Schedule) {
                    items(state.recentSources, key = { it.source.id }) { recent ->
                        // Resolved here, in the composable, rather than inside `onClick` — a
                        // click handler is not a composition and cannot read resources.
                        val label = sourceLabel(recent.source.kind)
                        CollectionCardFor(
                            viewModel = viewModel,
                            title = recent.source.name,
                            subtitle = label,
                            tracks = recent.tracks,
                            onClick = {
                                onOpenCollection(
                                    Collection(recent.source.name, label, recent.tracks, recent.source)
                                )
                            },
                        )
                    }
                }
            }
        }

        if (state.playlists.isNotEmpty()) {
            item {
                val playlistLabel = stringResource(R.string.playlist)
                Shelf(stringResource(R.string.your_playlists), null) {
                    items(playlists, key = { it.id }) { playlist ->
                        CollectionCardFor(
                            viewModel = viewModel,
                            title = playlist.name,
                            subtitle = trackCount(playlist.tracks.size),
                            tracks = playlist.tracks,
                            // Only Favourites gets the heart; every other playlist is a note.
                            fallbackIcon = if (playlist.name == PlayerViewModel.FAVORITES) {
                                AppIcons.Favorite
                            } else {
                                AppIcons.MusicNote
                            },
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
                // Titles and subtitles are read here, once, and reused for both the card and the
                // collection it opens — a click handler cannot read resources.
                val shuffleAllTitle = stringResource(R.string.shuffle_all)
                val allDownloaded = stringResource(R.string.all_downloaded_tracks)
                val recentlyAddedTitle = stringResource(R.string.recently_added)
                val fromDownloads = stringResource(R.string.from_your_downloads)
                val madeForYouTitle = stringResource(R.string.made_for_you)
                // Says what it is actually doing: with too few plays behind it the ranking is
                // not yet a recommendation, and claiming otherwise would be a small lie.
                val madeForYouSubtitle = stringResource(
                    if (viewModel.isModelTrained) R.string.based_on_your_listening
                    else R.string.learning_your_taste
                )

                Shelf(stringResource(R.string.recommended_for_you), AppIcons.AutoAwesome) {
                    item {
                        CollectionCardFor(
                            viewModel, shuffleAllTitle, allDownloaded, shuffleAll,
                            onClick = {
                                onOpenCollection(Collection(shuffleAllTitle, allDownloaded, shuffleAll, null))
                            },
                        )
                    }
                    item {
                        CollectionCardFor(
                            viewModel, recentlyAddedTitle, fromDownloads, recentlyAdded,
                            onClick = {
                                onOpenCollection(Collection(recentlyAddedTitle, fromDownloads, recentlyAdded, null))
                            },
                        )
                    }
                    item {
                        CollectionCardFor(
                            viewModel, madeForYouTitle, madeForYouSubtitle, madeForYou,
                            onClick = {
                                onOpenCollection(Collection(madeForYouTitle, madeForYouSubtitle, madeForYou, null))
                            },
                        )
                    }
                }
            }
        }

        val artists = state.artists
        if (artists.isNotEmpty()) {
            item {
                Shelf(stringResource(R.string.artists), AppIcons.Mic) {
                    items(artists, key = { it.first }) { (artist, tracks) ->
                        val files = tracks.map { it.driveMeta }
                        CollectionCardFor(
                            viewModel = viewModel,
                            title = artist,
                            subtitle = trackCount(tracks.size),
                            tracks = files,
                            fallbackIcon = AppIcons.Mic,
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
    @androidx.annotation.DrawableRes icon: Int?,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon?.let {
                Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
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
    @androidx.annotation.DrawableRes fallbackIcon: Int = AppIcons.MusicNote,
    onClick: () -> Unit,
) {
    val covers by produceState(initialValue = emptyList<ByteArray>(), tracks.take(4).map { it.id }) {
        value = viewModel.coversFor(tracks)
    }
    CollectionCard(title, subtitle, covers, fallbackIcon, onClick)
}

@Composable
private fun sourceLabel(kind: PlaySource.Kind) = stringResource(
    when (kind) {
        PlaySource.Kind.PLAYLIST -> R.string.playlist
        PlaySource.Kind.LIBRARY -> R.string.library
        PlaySource.Kind.FOLDER -> R.string.folder
        PlaySource.Kind.GENERATED -> R.string.mix
    }
)

/**
 * "3 tracks".
 *
 * A plural resource rather than string concatenation with an "s": Thai and Japanese have no
 * plural form at all, so appending a letter produces text that is wrong in both — and the catalog
 * translations already read naturally without one.
 */
@Composable
fun trackCount(count: Int): String =
    androidx.compose.ui.res.pluralStringResource(R.plurals.track_count, count, count)

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
fun EmptyState(message: String, title: String? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        title?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
        )
    }
}

/**
 * Opens search rather than being a field itself.
 *
 * A live field here would need the shelves to become results as you type, which is two screens
 * fighting over one list. A button says plainly that searching goes somewhere.
 */

/** How many items a browse shelf shows. */
private const val SHELF_LIMIT = 20

/**
 * "Good evening", as content rather than as a title bar.
 *
 * It reads as a headline here, which is what it always was — the top bar was giving a permanent,
 * pinned slot to a line that says nothing actionable and never changes within a session.
 */
@Composable
private fun GreetingHeader() {
    Text(
        greeting(),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
