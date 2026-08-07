package com.drivemusic.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
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
enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    BROWSE("Browse", Icons.Default.Folder),
    PLAYLISTS("Playlists", Icons.Default.PlaylistPlay),
    LIBRARY("Library", Icons.Default.LibraryMusic),
}

/** What is pushed on top of the current tab. */
private sealed interface Pushed {
    data class CollectionDetail(val collection: Collection) : Pushed
    data object Settings : Pushed
    data object Profile : Pushed
    data object Analytics : Pushed
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun AppShell(container: AppContainer, viewModel: PlayerViewModel, onSignOut: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.HOME) }
    var pushed by remember { mutableStateOf<Pushed?>(null) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var addingToPlaylist by remember { mutableStateOf<DriveFile?>(null) }
    var query by remember { mutableStateOf("") }

    // The library-backed tabs read what is downloaded, so they re-read on entry — after a
    // download, a playlist edit or a wipe the previous snapshot is stale.
    LaunchedEffect(tab, pushed) { if (tab != Tab.BROWSE) viewModel.refreshLibrary() }

    if (showNowPlaying && state.currentTrack != null) {
        NowPlayingScreen(state, viewModel) { showNowPlaying = false }
        return
    }

    when (val current = pushed) {
        is Pushed.CollectionDetail -> {
            CollectionDetailScreen(
                collection = current.collection,
                state = state,
                viewModel = viewModel,
                onAddToPlaylist = { addingToPlaylist = it },
                onBack = { pushed = null },
            )
            AddToPlaylistHost(addingToPlaylist, state, viewModel) { addingToPlaylist = null }
            return
        }
        Pushed.Settings -> {
            PushedScreen("Settings", { pushed = null }) {
                SettingsScreen(state, viewModel, onSignOut)
            }
            return
        }
        Pushed.Profile -> {
            PushedScreen("Profile", { pushed = null }) { ProfileScreen(container, state) }
            return
        }
        Pushed.Analytics -> {
            PushedScreen("Analytics", { pushed = null }) { AnalyticsScreen(state) }
            return
        }
        null -> Unit
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(tab.label) },
                navigationIcon = {
                    IconButton(onClick = { pushed = Pushed.Profile }) { ProfileAvatar(container) }
                },
                actions = {
                    IconButton(onClick = { pushed = Pushed.Analytics }) {
                        Icon(Icons.Default.Insights, contentDescription = "Analytics")
                    }
                    IconButton(onClick = { pushed = Pushed.Settings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            Column {
                MiniPlayer(state, viewModel) { showNowPlaying = true }
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry; query = "" },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            // Every section is searchable on iOS, so the field belongs above the content rather
            // than inside each screen.
            if (tab != Tab.BROWSE) {
                SearchField(query, "Search ${tab.label.lowercase()}") { query = it }
            }

            when (tab) {
                Tab.HOME -> HomeScreen(state, viewModel, query) { pushed = Pushed.CollectionDetail(it) }
                Tab.BROWSE -> BrowseScreen(
                    drive = container.drive,
                    downloadedIds = state.downloadedIds,
                    onPlay = viewModel::play,
                    onShuffle = { tracks, source -> viewModel.shufflePlay(tracks, source) },
                    onQueue = viewModel::addToQueue,
                    onAddToPlaylist = { addingToPlaylist = it },
                    onDownloadAll = viewModel::downloadAll,
                )
                Tab.PLAYLISTS -> PlaylistsScreen(state, viewModel, query) {
                    pushed = Pushed.CollectionDetail(it)
                }
                Tab.LIBRARY -> LibraryScreen(state, viewModel, query) { addingToPlaylist = it }
            }
        }
    }

    AddToPlaylistHost(addingToPlaylist, state, viewModel) { addingToPlaylist = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PushedScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

/** The account's initial, standing in for the avatar the iOS toolbar shows. */
@Composable
private fun ProfileAvatar(container: AppContainer) {
    val authState by container.auth.state.collectAsStateWithLifecycle()
    val email = (authState as? com.drivemusic.android.auth.GoogleAuth.State.Authorized)?.account?.email

    Box(
        modifier = Modifier.size(30.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            email?.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
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
