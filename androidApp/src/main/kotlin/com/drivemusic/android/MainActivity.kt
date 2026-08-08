package com.drivemusic.android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import com.drivemusic.android.auth.GoogleAuth
import com.drivemusic.android.player.PlayerViewModel
import androidx.compose.ui.platform.LocalContext
import com.drivemusic.android.player.AppTheme
import com.drivemusic.android.player.AppearanceStore
import com.drivemusic.android.ui.AppShell
import com.drivemusic.android.ui.SignInScreen
import com.drivemusic.android.ui.DriveMusicTheme

@UnstableApi
/**
 * `AppCompatActivity`, not `ComponentActivity`.
 *
 * The in-app language override goes through `AppCompatDelegate.setApplicationLocales`, and below
 * API 33 it is the AppCompat layer that re-applies the stored locale when an activity is created.
 * On a plain `ComponentActivity` the setting would persist and then do nothing on those devices.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Installed before `super.onCreate`, which is what the API requires: it swaps the
        // starting window's theme for the app's real one at the first frame, so there is no
        // moment where the app is drawn with the splash theme still applied.
        installSplashScreen()

        // Explicit rather than inherited from the platform default. From API 35 an app is
        // edge-to-edge whether it asks or not, so the choice is not *whether* content draws behind
        // the system bars but whether the app admits it and pads accordingly. Saying so here keeps
        // the behavior the same on older versions too, instead of it changing with the OS.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = AppContainer.get(this)

        setContent {
            // Read as state so a change on the Profile screen repaints immediately rather than
            // waiting for a relaunch, which is what the iOS `@AppStorage` binding gives for free.
            var theme by remember { mutableStateOf(AppearanceStore(this).theme) }

            DriveMusicTheme(theme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(container) { theme = it }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun AppRoot(container: AppContainer, onThemeChange: (AppTheme) -> Unit) {
    val authState by container.auth.state.collectAsState()

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        container.auth.onConsentResult(result.data)
    }

    // Attempts silent authorization on launch. Consent, when it is needed, is surfaced as a state
    // rather than launched from here — see `GoogleAuth`, which deliberately holds no Activity.
    LaunchedEffect(Unit) {
        if (authState is GoogleAuth.State.Checking) container.auth.authorize()
    }

    LaunchedEffect(authState) {
        (authState as? GoogleAuth.State.NeedsConsent)?.let { consentLauncher.launch(it.request) }
        // Read once per authorization rather than on every screen that wants it — the profile is
        // three strings and does not change while the app runs.
        if (authState is GoogleAuth.State.Authorized) {
            val info = container.drive.userInfo()
            container.auth.setAccount(info?.email, info?.name, info?.picture)
        }
    }

    when (authState) {
        // Nothing is known yet — a spinner, not the sign-in screen. Showing the latter here is
        // what made "Sign in with Google" flash on every launch of an already-signed-in app.
        GoogleAuth.State.Checking -> Centered { CircularProgressIndicator() }
        is GoogleAuth.State.Authorized -> SignedInApp(container, onThemeChange)
        is GoogleAuth.State.NeedsConsent -> Centered { Text(stringResource(R.string.waiting_for_permission)) }
        is GoogleAuth.State.Failed -> Centered {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text((authState as GoogleAuth.State.Failed).message)
                Button(onClick = { container.auth.signOut() }) { Text(stringResource(R.string.try_again)) }
            }
        }
        GoogleAuth.State.SignedOut -> {
            var busy by remember { mutableStateOf(false) }
            LaunchedEffect(busy) {
                if (busy) {
                    container.auth.authorize(interactive = true)
                    busy = false
                }
            }
            SignInScreen(
                isSigningIn = busy,
                // A failure surfaces as `State.Failed` and its own screen; this slot exists for a
                // sign-in that comes back here having gone nowhere.
                errorMessage = null,
                onSignIn = { busy = true },
            )
        }
    }
}

@UnstableApi
@Composable
private fun SignedInApp(container: AppContainer, onThemeChange: (AppTheme) -> Unit) {
    val application = LocalContext.current.applicationContext as android.app.Application
    val viewModel: PlayerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PlayerViewModel(application, container.library, container.files, container.drive)
            }
        }
    )
    AppShell(container, viewModel, onThemeChange) {
        container.auth.signOut()
        // Cached images were fetched with this account's token and belong to its files — they
        // must not survive into whoever signs in next.
        container.clearImageCaches()
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), Alignment.Center) { content() }
}
