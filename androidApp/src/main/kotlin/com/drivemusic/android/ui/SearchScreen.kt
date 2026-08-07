package com.drivemusic.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.drivemusic.android.R
import com.drivemusic.android.player.PlayerViewModel
import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.PlaySource

/**
 * Search, as its own screen rather than as a field wedged above Home's shelves.
 *
 * Searching is a mode, not an ornament: while it is happening the shelves are irrelevant and the
 * results want the whole screen. Home keeps a search *button*, and pressing it comes here with the
 * keyboard already up — one tap to start typing instead of tap-the-field-then-wait.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    query: String,
    onQueryChange: (String) -> Unit,
    onAddToPlaylist: (DriveFile) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Opened *to* search, so the field takes focus immediately.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val normalized = query.trim().lowercase()
    val matches = remember(state.cachedTracks, normalized) {
        if (normalized.isEmpty()) emptyList()
        else state.cachedTracks.filter { track ->
            listOfNotNull(
                track.driveMeta.name, track.parsedMeta.title,
                track.parsedMeta.artist, track.parsedMeta.album,
            ).any { it.lowercase().contains(normalized) }
        }
    }

    // The queue is the whole downloaded set, not the matches — playing a result should carry on
    // into the rest of the library rather than stopping at the end of a transient filter.
    val queue = remember(state.cachedTracks) { state.cachedTracks.map { it.driveMeta } }
    val source = remember { PlaySource("__library__", "", PlaySource.Kind.LIBRARY) }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.search_your_music)) },
            singleLine = true,
            leadingIcon = {
                Icon(painterResource(AppIcons.Search), contentDescription = null)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(painterResource(AppIcons.Close), contentDescription = stringResource(R.string.close))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .focusRequester(focusRequester),
        )

        when {
            normalized.isEmpty() -> Unit
            matches.isEmpty() -> Text(
                stringResource(R.string.no_tracks_match, query),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(matches, key = { it.fileId }) { track ->
                    TrackListRow(
                        file = track.driveMeta,
                        cachedTrack = track,
                        state = state,
                        viewModel = viewModel,
                        onClick = {
                            viewModel.play(queue, queue.indexOfFirst { it.id == track.fileId }, source)
                        },
                        onAddToPlaylist = { onAddToPlaylist(track.driveMeta) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}
