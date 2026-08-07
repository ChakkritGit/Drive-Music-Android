package com.drivemusic.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.drivemusic.android.AppContainer
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.shared.model.DriveFile

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    BROWSE("Browse", Icons.Default.Folder),
    LIBRARY("Library", Icons.Default.LibraryMusic),
    PLAYLISTS("Playlists", Icons.Default.PlaylistPlay),
    SETTINGS("Settings", Icons.Default.Settings),
}

/**
 * The signed-in app: five tabs with the mini player pinned above the bar.
 *
 * Now Playing is a full-screen takeover rather than another destination, so dismissing it returns
 * to whatever tab was underneath — the same relationship the iOS app's `fullScreenCover` has.
 */
@UnstableApi
@Composable
fun AppShell(container: AppContainer, viewModel: PlayerViewModel, onSignOut: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.HOME) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var addingToPlaylist by remember { mutableStateOf<DriveFile?>(null) }

    // The library tabs read from what is downloaded, so they have to re-read after anything that
    // changes it — a download finishing, a playlist edit, a wipe.
    LaunchedEffect(tab) { if (tab != Tab.BROWSE) viewModel.refreshLibrary() }

    if (showNowPlaying && state.currentTrack != null) {
        NowPlayingScreen(state, viewModel) { showNowPlaying = false }
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Column {
                MiniPlayer(state, viewModel) { showNowPlaying = true }
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(state, viewModel)
                Tab.BROWSE -> BrowseScreen(
                    drive = container.drive,
                    downloadedIds = state.downloadedIds,
                    onPlay = viewModel::play,
                    onShuffle = { tracks, source -> viewModel.shufflePlay(tracks, source) },
                    onQueue = viewModel::addToQueue,
                    onAddToPlaylist = { addingToPlaylist = it },
                    onDownloadAll = viewModel::downloadAll,
                )
                Tab.LIBRARY -> LibraryScreen(state, viewModel) { addingToPlaylist = it }
                Tab.PLAYLISTS -> PlaylistsScreen(state, viewModel)
                Tab.SETTINGS -> SettingsScreen(state, viewModel, onSignOut)
            }

            state.downloadProgress?.let { (done, total) ->
                Text(
                    "Downloading $done of $total",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    addingToPlaylist?.let { file ->
        AddToPlaylistDialog(
            file = file,
            playlists = state.playlists,
            onDismiss = { addingToPlaylist = null },
            onPick = { viewModel.addToPlaylist(it, file) },
            onCreate = { name ->
                viewModel.createPlaylist(name)
                // The playlist is created asynchronously, so the track is added on the next
                // refresh rather than here — see `PlaylistsScreen` for adding to an existing one.
            },
        )
    }
}
