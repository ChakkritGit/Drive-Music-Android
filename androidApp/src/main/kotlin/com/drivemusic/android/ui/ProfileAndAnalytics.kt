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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivemusic.android.R
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
                        account?.name ?: stringResource(R.string.signed_in),
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

        SettingsSection(stringResource(R.string.preferences))
        SettingsGroup {
            ChoiceRow(
                icon = AppIcons.Contrast,
                label = stringResource(R.string.appearance),
                options = AppTheme.entries.map { it to stringResource(it.labelRes) },
                selected = theme,
            ) {
                theme = it
                appearance.theme = it
                // Repaints now rather than on next launch, matching the iOS `@AppStorage` binding.
                onThemeChange(it)
            }

            SettingsDivider()

            ChoiceRow(
                icon = AppIcons.Language,
                label = stringResource(R.string.language),
                options = AppLanguage.entries.map { it to it.nativeName },
                selected = language,
            ) { language = it; appearance.language = it }
        }

        SettingsSection(stringResource(R.string.about))
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
                    Icon(painterResource(page.icon), contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    Text(stringResource(page.titleRes), modifier = Modifier.weight(1f))
                    Icon(painterResource(AppIcons.OpenInNew),
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
                Icon(painterResource(AppIcons.Logout),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text(stringResource(R.string.sign_out_of_drive_music)) },
            text = { Text(stringResource(R.string.you_ll_need_to_sign_in_again_to_access_your_drive_library)) },
            confirmButton = {
                TextButton(onClick = { confirmingSignOut = false; onSignOut() }) {
                    Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSignOut = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** The two documents the web app serves — linked rather than shipped, so wording changes need no release. */
enum class LegalPage(
    @androidx.annotation.StringRes val titleRes: Int,
    val url: String,
    @androidx.annotation.DrawableRes val icon: Int,
) {
    PRIVACY(R.string.privacy_policy, "https://drive-music-taupe.vercel.app/privacy", AppIcons.Shield),
    TERMS(R.string.terms_of_service, "https://drive-music-taupe.vercel.app/terms", AppIcons.Description),
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
            Icon(painterResource(AppIcons.AccountCircle),
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
    @androidx.annotation.DrawableRes icon: Int,
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
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        Text(label, modifier = Modifier.weight(1f))

        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    options.firstOrNull { it.first == selected }?.second.orEmpty(),
                    color = MaterialTheme.colorScheme.outline,
                )
                Icon(
                    painterResource(if (expanded) AppIcons.ArrowDropUp else AppIcons.ArrowDropDown),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
            AppMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, title) ->
                    AppMenuItem(
                        label = title,
                        onClick = { onSelect(value); expanded = false },
                        icon = AppIcons.Check.takeIf { value == selected },
                        tint = MaterialTheme.colorScheme.primary,
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
        Text(stringResource(R.string.library), style = MaterialTheme.typography.titleMedium)
        StatRow(stringResource(R.string.downloaded_tracks), state.cachedTracks.size.toString())
        StatRow(stringResource(R.string.artists), state.artists.size.toString())
        StatRow(stringResource(R.string.playlists), state.playlists.size.toString())
        StatRow(stringResource(R.string.recently_played_sources), state.recentSources.size.toString())
        StatRow(stringResource(R.string.storage_used), formatBytes(state.cacheBytes))

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text(stringResource(R.string.most_played_sources), style = MaterialTheme.typography.titleMedium)
        if (state.recentSources.isEmpty()) {
            Text(
                stringResource(R.string.nothing_played_yet),
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
