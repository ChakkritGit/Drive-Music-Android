package com.drivemusic.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.drivemusic.android.R
import com.drivemusic.android.AppContainer
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.shared.model.DriveFile

/**
 * The four top-level sections, in the iOS order.
 *
 * Settings is deliberately *not* among them. On iOS it is a toolbar button present on every tab,
 * alongside the profile avatar and the analytics view — a persistent header, not a destination.
 * Putting it in the bottom bar gives a rarely-used screen the same weight as the four the app is
 * actually for.
 */
enum class Tab(
    @androidx.annotation.StringRes val labelRes: Int,
    @androidx.annotation.DrawableRes val iconRes: Int,
) {
    HOME(R.string.home, AppIcons.Home),
    BROWSE(R.string.browse, AppIcons.Folder),
    PLAYLISTS(R.string.playlists, AppIcons.PlaylistPlay),
    LIBRARY(R.string.library, AppIcons.LibraryMusic),
}

/** What is pushed on top of the current tab. */
private sealed interface Pushed {
    data class CollectionDetail(val collection: Collection) : Pushed
    data object Settings : Pushed
    data object Profile : Pushed
    data object Analytics : Pushed
    data object Search : Pushed
}

/**
 * The signed-in app.
 *
 * Wrapped in a [SharedTransitionLayout] so the artwork can travel between the mini player and the
 * full Now Playing screen rather than one being replaced by the other — the container transform
 * that makes the two read as the same thing at two sizes, which is the whole point of a mini
 * player. Without it the expansion is a cut, and the relationship between the bar and the screen
 * has to be inferred.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@UnstableApi
@Composable
fun AppShell(
    container: AppContainer,
    viewModel: PlayerViewModel,
    onThemeChange: (com.drivemusic.android.player.AppTheme) -> Unit,
    onSignOut: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showNowPlaying by remember { mutableStateOf(false) }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = showNowPlaying && state.currentTrack != null,
            transitionSpec = {
                // Expanding grows from the bar; collapsing settles back into it. Fading both ways
                // keeps the surfaces from sliding over each other while the artwork moves.
                (fadeIn(tween(320)) togetherWith fadeOut(tween(220)))
                    .using(SizeTransform(clip = false))
            },
            label = "now-playing",
        ) { expanded ->
            if (expanded) {
                NowPlayingScreen(
                    state = state,
                    viewModel = viewModel,
                    sharedScope = this@SharedTransitionLayout,
                    animatedScope = this@AnimatedContent,
                    onDismiss = { showNowPlaying = false },
                )
            } else {
                AppShellContent(
                    container = container,
                    viewModel = viewModel,
                    state = state,
                    sharedScope = this@SharedTransitionLayout,
                    animatedScope = this@AnimatedContent,
                    onExpand = { showNowPlaying = true },
                    onThemeChange = onThemeChange,
                    onSignOut = onSignOut,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@UnstableApi
@Composable
private fun AppShellContent(
    container: AppContainer,
    viewModel: PlayerViewModel,
    state: PlayerViewModel.UiState,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onExpand: () -> Unit,
    onThemeChange: (com.drivemusic.android.player.AppTheme) -> Unit,
    onSignOut: () -> Unit,
) {

    var tab by remember { mutableStateOf(Tab.HOME) }
    var pushed by remember { mutableStateOf<Pushed?>(null) }
    var addingToPlaylist by remember { mutableStateOf<DriveFile?>(null) }
    var query by remember { mutableStateOf("") }

    // The library-backed tabs read what is downloaded, so they re-read on entry — after a
    // download, a playlist edit or a wipe the previous snapshot is stale.
    LaunchedEffect(tab, pushed) { if (tab != Tab.BROWSE) viewModel.refreshLibrary() }

    // Android's Back gesture pops a pushed screen before leaving the app, same as the iOS
    // NavigationStack it mirrors.
    androidx.activity.compose.BackHandler(enabled = pushed != null) { pushed = null }

    // Home is titled by the time of day rather than by its own name — the section is obvious
    // from the highlighted tab, so the title is free to say something else.
    val homeTitle = greeting()

    val pushedTitle = when (pushed) {
        is Pushed.CollectionDetail -> (pushed as Pushed.CollectionDetail).collection.title
        Pushed.Settings -> stringResource(R.string.settings)
        Pushed.Profile -> stringResource(R.string.profile)
        Pushed.Analytics -> stringResource(R.string.analytics)
        // The field itself says what this screen is; a title above it would repeat that.
        Pushed.Search -> ""
        null -> null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        pushedTitle
                            ?: if (tab == Tab.HOME) homeTitle else stringResource(tab.labelRes),
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    if (pushed != null) {
                        IconButton(onClick = { pushed = null }) {
                            Icon(painterResource(AppIcons.ArrowBack), contentDescription = stringResource(R.string.back))
                        }
                    } else {
                        IconButton(onClick = { pushed = Pushed.Profile }) { ProfileAvatar(container) }
                    }
                },
                actions = {
                    // The header buttons stay on the top-level screens only — inside a pushed one
                    // the bar belongs to that screen.
                    if (pushed == null) {
                        IconButton(onClick = { pushed = Pushed.Analytics }) {
                            Icon(painterResource(AppIcons.Insights), contentDescription = stringResource(R.string.analytics))
                        }
                        IconButton(onClick = { pushed = Pushed.Settings }) {
                            Icon(painterResource(AppIcons.Settings), contentDescription = stringResource(R.string.settings))
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                MiniPlayer(state, viewModel, sharedScope, animatedScope, onExpand)
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry && pushed == null,
                            // Tapping a tab also pops whatever is pushed — the tab is the
                            // destination, and landing on a detail screen belonging to a different
                            // section would be the wrong place.
                            onClick = { tab = entry; pushed = null; query = "" },
                            icon = {
                                Icon(painterResource(entry.iconRes), contentDescription = stringResource(entry.labelRes))
                            },
                            label = { Text(stringResource(entry.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            // Pushed screens slide in from the trailing edge and back out, which is what a push
            // means; the top-level sections underneath cross-fade, because they are peers with no
            // left-to-right order and sliding between them would imply one.
            AnimatedContent(
                targetState = pushed,
                transitionSpec = {
                    if (targetState != null) {
                        (slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(260)))
                            .togetherWith(fadeOut(tween(160)))
                    } else {
                        fadeIn(tween(200))
                            .togetherWith(slideOutHorizontally(tween(240)) { it / 4 } + fadeOut(tween(200)))
                    }
                },
                label = "pushed",
            ) { pushedNow ->
                when (pushedNow) {
                    is Pushed.CollectionDetail -> CollectionDetailScreen(
                        collection = pushedNow.collection,
                        state = state,
                        viewModel = viewModel,
                        onAddToPlaylist = { addingToPlaylist = it },
                    )

                    Pushed.Settings -> SettingsScreen(state, viewModel)
                    Pushed.Profile -> ProfileScreen(container, state, onThemeChange, onSignOut)
                    Pushed.Analytics -> AnalyticsScreen(state)
                    Pushed.Search -> SearchScreen(
                        state = state,
                        viewModel = viewModel,
                        query = query,
                        onQueryChange = { query = it },
                        onAddToPlaylist = { addingToPlaylist = it },
                    )

                    null -> Column(modifier = Modifier.fillMaxSize()) {
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                fadeIn(tween(200)).togetherWith(fadeOut(tween(150)))
                            },
                            label = "tab",
                        ) { current ->
                            when (current) {
                                Tab.HOME -> HomeScreen(
                                    state = state,
                                    viewModel = viewModel,
                                    onOpenSearch = { query = ""; pushed = Pushed.Search },
                                ) { pushed = Pushed.CollectionDetail(it) }

                                Tab.BROWSE -> BrowseScreen(
                                    drive = container.drive,
                                    playerState = state,
                                    viewModel = viewModel,
                                    downloadedIds = state.downloadedIds,
                                    onPlay = viewModel::play,
                                    onShuffle = { tracks, source -> viewModel.shufflePlay(tracks, source) },
                                    onQueue = viewModel::addToQueue,
                                    onAddToPlaylist = { addingToPlaylist = it },
                                    onDownloadAll = viewModel::downloadAll,
                                    downloadProgress = state.downloadProgress,
                                )

                                Tab.PLAYLISTS -> PlaylistsScreen(
                                    state = state,
                                    viewModel = viewModel,
                                    query = query,
                                    onQueryChange = { query = it },
                                ) { pushed = Pushed.CollectionDetail(it) }

                                Tab.LIBRARY -> LibraryScreen(
                                    state = state,
                                    viewModel = viewModel,
                                    query = query,
                                    onQueryChange = { query = it },
                                ) { addingToPlaylist = it }
                            }
                        }
                    }
                }
            }
        }
    }

    AddToPlaylistHost(addingToPlaylist, state, viewModel) { addingToPlaylist = null }
}

/**
 * "Good morning" / "Good afternoon" / "Good evening", by the device's own clock.
 *
 * Read through `stringResource` rather than resolved to a `String` elsewhere, so it follows the
 * app's in-app language override rather than the device's system language.
 */
@Composable
private fun greeting(): String {
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    return stringResource(
        when {
            hour < 12 -> R.string.good_morning
            hour < 18 -> R.string.good_afternoon
            else -> R.string.good_evening
        }
    )
}

/** The account's initial, standing in for the avatar the iOS toolbar shows. */
@Composable
private fun ProfileAvatar(container: AppContainer) {
    val authState by container.auth.state.collectAsStateWithLifecycle()
    val account = (authState as? com.drivemusic.android.auth.GoogleAuth.State.Authorized)?.account

    Box(modifier = Modifier.size(30.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
        // The picture, when there is one. This used to render only the fallback, so the initial
        // stood in for the photo permanently.
        if (account?.pictureUrl != null) {
            AsyncImage(
                model = account.pictureUrl,
                contentDescription = stringResource(R.string.profile),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(painterResource(AppIcons.AccountCircle),
                contentDescription = stringResource(R.string.profile),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AddToPlaylistHost(
    file: DriveFile?,
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    file ?: return
    AddToPlaylistDialog(
        file = file,
        playlists = state.playlists,
        onDismiss = onDismiss,
        onPick = { viewModel.addToPlaylist(it, file) },
        onCreate = { viewModel.createPlaylist(it) },
    )
}
