package com.drivemusic.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import com.drivemusic.android.auth.GoogleAuth
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.android.ui.BrowseScreen
import com.drivemusic.android.ui.MiniPlayer
import com.drivemusic.android.ui.NowPlayingScreen

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = AppContainer.get(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(container)
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun AppRoot(container: AppContainer) {
    val authState by container.auth.state.collectAsState()

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        container.auth.onConsentResult(result.data)
    }

    // Attempts silent authorization on launch. Consent, when it is needed, is surfaced as a state
    // rather than launched from here — see `GoogleAuth`, which deliberately holds no Activity.
    LaunchedEffect(Unit) {
        if (authState is GoogleAuth.State.SignedOut) container.auth.authorize()
    }

    LaunchedEffect(authState) {
        (authState as? GoogleAuth.State.NeedsConsent)?.let { consentLauncher.launch(it.request) }
    }

    when (authState) {
        is GoogleAuth.State.Authorized -> SignedInApp(container)
        is GoogleAuth.State.NeedsConsent -> Centered { Text("Waiting for permission…") }
        is GoogleAuth.State.Failed -> Centered {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text((authState as GoogleAuth.State.Failed).message)
                Button(onClick = { container.auth.signOut() }) { Text("Try again") }
            }
        }
        GoogleAuth.State.SignedOut -> Centered {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Drive Music", style = MaterialTheme.typography.headlineMedium)
                Text("Sign in to play the music in your Google Drive.")
                SignInButton(container)
            }
        }
    }
}

@Composable
private fun SignInButton(container: AppContainer) {
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { if (busy) { container.auth.authorize(); busy = false } }
    Button(onClick = { busy = true }, enabled = !busy) {
        Text(if (busy) "Connecting…" else "Connect Google Drive")
    }
}

@UnstableApi
@Composable
private fun SignedInApp(container: AppContainer) {
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    val viewModel: PlayerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PlayerViewModel(application, container.library, container.files, container.drive)
            }
        }
    )
    val state by viewModel.state.collectAsState()
    var showNowPlaying by remember { mutableStateOf(false) }

    if (showNowPlaying && state.currentTrack != null) {
        NowPlayingScreen(state, viewModel) { showNowPlaying = false }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            BrowseScreen(
                drive = container.drive,
                downloadedIds = state.downloadedIds,
                onPlay = viewModel::play,
                onShuffle = { tracks, source -> viewModel.shufflePlay(tracks, source) },
                onQueue = viewModel::addToQueue,
            )
        }
        state.error?.let {
            Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        }
        MiniPlayer(state, viewModel) { showNowPlaying = true }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) { content() }
}
