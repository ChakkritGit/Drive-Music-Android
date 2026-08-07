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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.drivemusic.android.R
import com.drivemusic.android.audio.EqSettings
import com.drivemusic.android.player.PlayerViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(state: PlayerViewModel.UiState, viewModel: PlayerViewModel) {
    var confirmingWipe by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        SettingsSection(stringResource(R.string.transitions))
        SettingsGroup {
        SwitchRow(
            title = stringResource(R.string.crossfade),
            subtitle = stringResource(R.string.crossfade_detail),
            checked = state.crossfadeEnabled,
            onChange = viewModel::setCrossfadeEnabled,
        )
        if (state.crossfadeEnabled) {
            SliderRow(
                title = stringResource(R.string.length),
                value = state.crossfadeSeconds.toFloat(),
                range = 2f..20f,
                display = "${state.crossfadeSeconds.roundToInt()}s",
                onChange = { viewModel.setCrossfadeSeconds(it.toDouble()) },
            )
            SwitchRow(
                title = stringResource(R.string.auto_mix),
                subtitle = stringResource(R.string.auto_mix_detail),
                checked = state.autoMixEnabled,
                onChange = viewModel::setAutoMixEnabled,
            )
        }
        SettingsDivider(startInset = 16.dp)
        SwitchRow(
            title = stringResource(R.string.gapless_playback),
            subtitle = stringResource(R.string.gapless_detail),
            checked = state.gaplessEnabled,
            onChange = viewModel::setGaplessEnabled,
        )
        }

        // Its own section, as on iOS: this is the only setting that changes how the Now Playing
        // screen looks rather than how playback sounds, and filing it under Sound would be wrong.
        SettingsSection(stringResource(R.string.now_playing))
        SettingsGroup {
            SwitchRow(
                title = stringResource(R.string.ambient_light),
                subtitle = stringResource(R.string.shows_a_soft_audio_reactive_glow_behind_the_artwork_on_the_n),
                checked = state.ambientGlowEnabled,
                onChange = viewModel::setAmbientGlowEnabled,
            )
        }

        SettingsSection(stringResource(R.string.sound))
        SettingsGroup {
        SwitchRow(
            title = stringResource(R.string.volume_normalization),
            subtitle = stringResource(R.string.normalization_detail),
            checked = state.volumeNormalizationEnabled,
            onChange = viewModel::setVolumeNormalizationEnabled,
        )
        SettingsDivider(startInset = 16.dp)
        SwitchRow(
            title = stringResource(R.string.equalizer),
            subtitle = stringResource(R.string.eq_detail),
            checked = state.eq.enabled,
            onChange = { viewModel.setEq(state.eq.copy(enabled = it)) },
        )
        if (state.eq.enabled) {
            EqBand(stringResource(R.string.bass), state.eq.bassDb) { viewModel.setEq(state.eq.copy(bassDb = it)) }
            EqBand(stringResource(R.string.mid), state.eq.midDb) { viewModel.setEq(state.eq.copy(midDb = it)) }
            EqBand(stringResource(R.string.treble), state.eq.trebleDb) { viewModel.setEq(state.eq.copy(trebleDb = it)) }
            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = {
                    viewModel.setEq(state.eq.copy(bassDb = 0.0, midDb = 0.0, trebleDb = 0.0))
                }) { Text(stringResource(R.string.reset_to_flat)) }
            }
        }
        }

        SettingsSection(stringResource(R.string.storage))
        SettingsGroup {
            Text(
                "${stringResource(R.string.track_count, state.cachedTracks.size)} · ${formatBytes(state.cacheBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        SettingsSection(stringResource(R.string.danger_zone))
        Row(modifier = Modifier.padding(16.dp)) {
            Button(
                onClick = { confirmingWipe = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text(stringResource(R.string.clear_all_data)) }
        }
        Text(
            stringResource(R.string.clear_all_data_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp),
        )
    }

    if (confirmingWipe) {
        AlertDialog(
            onDismissRequest = { confirmingWipe = false },
            title = { Text(stringResource(R.string.clear_all_data_2)) },
            text = { Text(stringResource(R.string.clear_all_data_confirm)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearAllData(); confirmingWipe = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.delete_everything)) }
            },
            dismissButton = { TextButton(onClick = { confirmingWipe = false }) { Text(stringResource(R.string.cancel)) } },
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
