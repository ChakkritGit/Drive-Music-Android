package com.drivemusic.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivemusic.android.AppContainer
import com.drivemusic.android.auth.GoogleAuth
import coil.compose.AsyncImage
import com.drivemusic.android.player.AppLanguage
import com.drivemusic.android.player.AppTheme
import com.drivemusic.android.player.AppearanceStore
import com.drivemusic.android.player.PlayerViewModel

/**
 * Account, preferences, the legal pages, and sign-out.
 *
 * Sections and wording follow the iOS `ProfileView` — including what is *not* here. Library
 * counts and storage live on the analytics and settings screens; repeating them here would make
 * this a second settings screen rather than an account one.
 */
@Composable
fun ProfileScreen(
    container: AppContainer,
    state: PlayerViewModel.UiState,
    onThemeChange: (AppTheme) -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    val appearance = remember { AppearanceStore(context) }
    val authState by container.auth.state.collectAsStateWithLifecycle()
    val account = (authState as? GoogleAuth.State.Authorized)?.account

    var theme by remember { mutableStateOf(appearance.theme) }
    var language by remember { mutableStateOf(appearance.language) }
    var confirmingSignOut by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Account, headerless — the row says what it is.
        SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(account?.pictureUrl, size = 56.dp)
                Column {
                    Text(
                        account?.name ?: "Signed in",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    account?.email?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        SettingsSection("Preferences")
        SettingsGroup {
            ChoiceRow(
                icon = Icons.Default.Contrast,
                label = "Appearance",
                options = AppTheme.entries.map { it to it.title },
                selected = theme,
            ) {
                theme = it
                appearance.theme = it
                // Repaints now rather than on next launch, matching the iOS `@AppStorage` binding.
                onThemeChange(it)
            }

            SettingsDivider()

            ChoiceRow(
                icon = Icons.Default.Language,
                label = "Language",
                options = AppLanguage.entries.map { it to it.nativeName },
                selected = language,
            ) { language = it; appearance.language = it }
        }

        SettingsSection("About")
        SettingsGroup {
            LegalPage.entries.forEachIndexed { index, page ->
                if (index > 0) SettingsDivider()
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { openLegalPage(context, page) }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(page.icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    Text(page.title, modifier = Modifier.weight(1f))
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        // Destructive last, which is where a reader expects to find it.
        SettingsGroup(modifier = Modifier.padding(top = 12.dp, bottom = 32.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { confirmingSignOut = true }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text("Sign Out", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text("Sign out of Drive Music?") },
            text = { Text("You'll need to sign in again to access your Drive library.") },
            confirmButton = {
                TextButton(onClick = { confirmingSignOut = false; onSignOut() }) {
                    Text("Sign Out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingSignOut = false }) { Text("Cancel") } },
        )
    }
}

/** The two documents the web app serves — linked rather than shipped, so wording changes need no release. */
enum class LegalPage(val title: String, val url: String, val icon: ImageVector) {
    PRIVACY("Privacy Policy", "https://drive-music-taupe.vercel.app/privacy", Icons.Default.Shield),
    TERMS("Terms of Service", "https://drive-music-taupe.vercel.app/terms", Icons.Default.Description),
}

/**
 * Opens a legal page in a Custom Tab rather than handing off to a browser app.
 *
 * The page stays inside the app's task, keeps the app's colours, and comes back with the system
 * Back gesture — the closest thing to iOS pushing a `WKWebView`, without shipping a WebView and
 * having to own its lifecycle and security surface.
 */
private fun openLegalPage(context: android.content.Context, page: LegalPage) {
    runCatching {
        CustomTabsIntent.Builder().setShowTitle(true).build()
            .launchUrl(context, android.net.Uri.parse(page.url))
    }
}

@Composable
private fun Avatar(url: String?, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * A row that expands a Material 3 menu of choices — the equivalent of a SwiftUI `Picker` row.
 *
 * The menu is anchored to the *value*, not to the row, so it expands out of the thing being
 * changed rather than appearing over on the left across the label. The current choice carries a
 * check, since a menu that shows options without marking the active one makes you remember what
 * you were looking at a moment ago.
 */
@Composable
private fun <T> ChoiceRow(
    icon: ImageVector,
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        Text(label, modifier = Modifier.weight(1f))

        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    options.firstOrNull { it.first == selected }?.second.orEmpty(),
                    color = MaterialTheme.colorScheme.outline,
                )
                Icon(
                    // Rotates to point up while open, so the control says which way it will go.
                    if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                // Aligned to the value's trailing edge rather than spilling further right.
                offset = DpOffset(x = 0.dp, y = 4.dp),
            ) {
                options.forEach { (value, title) ->
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = { onSelect(value); expanded = false },
                        trailingIcon = {
                            if (value == selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * What the recommendation model has learned.
 *
 * The iOS version draws the network itself — every weight as an edge, animated on each training
 * step. That is not ported: it needs the model's internals exposed to the UI, and the useful part
 * of it is the summary rather than the picture.
 */
@Composable
fun AnalyticsScreen(state: PlayerViewModel.UiState) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Library", style = MaterialTheme.typography.titleMedium)
        StatRow("Downloaded tracks", state.cachedTracks.size.toString())
        StatRow("Artists", state.artists.size.toString())
        StatRow("Playlists", state.playlists.size.toString())
        StatRow("Recently played sources", state.recentSources.size.toString())
        StatRow("Storage used", formatBytes(state.cacheBytes))

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text("Most played sources", style = MaterialTheme.typography.titleMedium)
        if (state.recentSources.isEmpty()) {
            Text(
                "Nothing played yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            state.recentSources.sortedByDescending { it.playCount }.forEach { recent ->
                StatRow(recent.source.name, "${recent.playCount}×")
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    }
}
