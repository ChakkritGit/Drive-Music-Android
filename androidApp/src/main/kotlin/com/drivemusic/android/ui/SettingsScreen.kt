package com.drivemusic.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drivemusic.android.audio.EqSettings
import com.drivemusic.android.player.PlayerViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(state: PlayerViewModel.UiState, viewModel: PlayerViewModel) {
    var confirmingWipe by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        SettingsSection("Transitions")
        SettingsGroup {
        SwitchRow(
            title = "Crossfade",
            subtitle = "Blend one track into the next instead of cutting",
            checked = state.crossfadeEnabled,
            onChange = viewModel::setCrossfadeEnabled,
        )
        if (state.crossfadeEnabled) {
            SliderRow(
                title = "Length",
                value = state.crossfadeSeconds.toFloat(),
                range = 2f..20f,
                display = "${state.crossfadeSeconds.roundToInt()}s",
                onChange = { viewModel.setCrossfadeSeconds(it.toDouble()) },
            )
            SwitchRow(
                title = "Auto mix",
                subtitle = "Shape the crossfade as a DJ-style bass swap rather than a plain fade",
                checked = state.autoMixEnabled,
                onChange = viewModel::setAutoMixEnabled,
            )
        }
        SwitchRow(
            title = "Gapless",
            subtitle = "Start the next track the instant this one ends",
            checked = state.gaplessEnabled,
            onChange = viewModel::setGaplessEnabled,
        )
        }

        SettingsSection("Sound")
        SettingsGroup {
        SwitchRow(
            title = "Volume normalization",
            subtitle = "Even out tracks mastered at different levels",
            checked = state.volumeNormalizationEnabled,
            onChange = viewModel::setVolumeNormalizationEnabled,
        )
        SwitchRow(
            title = "Equalizer",
            subtitle = "Bass, mid and treble tone controls",
            checked = state.eq.enabled,
            onChange = { viewModel.setEq(state.eq.copy(enabled = it)) },
        )
        if (state.eq.enabled) {
            EqBand("Bass", state.eq.bassDb) { viewModel.setEq(state.eq.copy(bassDb = it)) }
            EqBand("Mid", state.eq.midDb) { viewModel.setEq(state.eq.copy(midDb = it)) }
            EqBand("Treble", state.eq.trebleDb) { viewModel.setEq(state.eq.copy(trebleDb = it)) }
            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = {
                    viewModel.setEq(state.eq.copy(bassDb = 0.0, midDb = 0.0, trebleDb = 0.0))
                }) { Text("Reset to flat") }
            }
        }
        }

        SettingsSection("Storage")
        SettingsGroup {
            Text(
                "${state.cachedTracks.size} tracks downloaded · ${formatBytes(state.cacheBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        SettingsSection("Danger zone")
        Row(modifier = Modifier.padding(16.dp)) {
            Button(
                onClick = { confirmingWipe = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Clear all data") }
        }
        Text(
            "Removes every download, playlist and listening history from this device. " +
                "Nothing in your Google Drive is touched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp),
        )
    }

    if (confirmingWipe) {
        AlertDialog(
            onDismissRequest = { confirmingWipe = false },
            title = { Text("Clear all data?") },
            text = { Text("Downloads, playlists and listening history will be deleted from this device. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearAllData(); confirmingWipe = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Delete everything") }
            },
            dismissButton = { TextButton(onClick = { confirmingWipe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun EqBand(label: String, valueDb: Double, onChange: (Double) -> Unit) {
    SliderRow(
        title = label,
        value = valueDb.toFloat(),
        range = (-EqSettings.MAX_GAIN_DB).toFloat()..EqSettings.MAX_GAIN_DB.toFloat(),
        display = "%+.0f dB".format(valueDb),
        onChange = { onChange(it.toDouble()) },
    )
}
