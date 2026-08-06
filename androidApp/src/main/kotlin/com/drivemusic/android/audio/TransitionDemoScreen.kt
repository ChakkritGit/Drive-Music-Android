package com.drivemusic.android.audio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.drivemusic.shared.transition.TransitionPreset
import kotlin.math.roundToInt

/**
 * A bench for listening to one transition between two files.
 *
 * Everything else about the audio path is verified offline — the coefficients by measurement, the
 * chain end to end by pushing signals through it. What none of that can answer is whether the
 * result sounds like a DJ mix, and that question decides whether the rest of the Android port is
 * worth building on this engine at all. Hence a screen whose only job is to make it audible early.
 *
 * Files are chosen through the system picker rather than a library, because there is no library
 * yet: this deliberately needs no auth, no networking and no persistence, so it can be used now
 * rather than after all three exist.
 */
@UnstableApi
@Composable
fun TransitionDemoScreen(viewModel: TransitionDemoViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val pickA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::setTrackA)
    }
    val pickB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::setTrackB)
    }
    val audioTypes = arrayOf("audio/*")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Transition bench", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Plays track A, then mixes into track B using the shared transition curves.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        TrackRow("A", state.trackA?.lastPathSegment) { pickA.launch(audioTypes) }
        TrackRow("B", state.trackB?.lastPathSegment) { pickB.launch(audioTypes) }

        OutlinedButton(onClick = viewModel::swapTracks, enabled = state.canPlay) {
            Text("Swap A and B")
        }

        HorizontalDivider()

        Text("Preset", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransitionPreset.entries.forEach { preset ->
                FilterChip(
                    selected = state.preset == preset,
                    onClick = { viewModel.setPreset(preset) },
                    label = { Text(preset.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        Text(
            "Length: ${state.durationSeconds.roundToInt()}s",
            style = MaterialTheme.typography.titleSmall,
        )
        Slider(
            value = state.durationSeconds.toFloat(),
            onValueChange = { viewModel.setDuration(it.toDouble()) },
            valueRange = 2f..30f,
        )

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::start, enabled = state.canPlay && !state.isPlaying) {
                Text("Play A")
            }
            Button(
                onClick = viewModel::runTransition,
                enabled = state.isPlaying && !state.isTransitioning,
            ) {
                Text("Mix into B")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::cancel, enabled = state.isTransitioning) {
                Text("Cancel mid-mix")
            }
            OutlinedButton(onClick = viewModel::stop, enabled = state.isPlaying) {
                Text("Stop")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                state.status,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Stated on the screen itself, not only in the commit message — anyone judging the sound
        // needs to know what is not in it yet, or they will be judging the wrong thing.
        Text(
            "Not applied yet: reverb (Rise leans on it, Mix uses it lightly and late), " +
                "the outgoing loop, and pitch-preserving beatmatch. What you are hearing is the " +
                "volume lanes, the filter sweeps and the bass swap.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TrackRow(label: String, name: String?, onPick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onPick) { Text("Track $label") }
        Text(
            name ?: "none",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
