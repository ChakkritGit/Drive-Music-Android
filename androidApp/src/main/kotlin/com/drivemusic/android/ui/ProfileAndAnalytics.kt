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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivemusic.android.AppContainer
import com.drivemusic.android.auth.GoogleAuth
import com.drivemusic.android.player.PlayerViewModel

@Composable
fun ProfileScreen(container: AppContainer, state: PlayerViewModel.UiState) {
    val authState by container.auth.state.collectAsStateWithLifecycle()
    val email = (authState as? GoogleAuth.State.Authorized)?.account?.email

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                email?.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(email ?: "Signed in", style = MaterialTheme.typography.titleMedium)
        Text(
            "Connected to Google Drive, read-only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        StatRow("Downloaded tracks", state.cachedTracks.size.toString())
        StatRow("Playlists", state.playlists.size.toString())
        StatRow("Storage used", formatBytes(state.cacheBytes))
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
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    }
}
