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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
import com.drivemusic.shared.model.TrackAnalysis
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import com.drivemusic.shared.recommendation.Features
import com.drivemusic.shared.recommendation.ModelEvent
import kotlin.math.roundToInt
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
fun AnalyticsScreen(state: PlayerViewModel.UiState, viewModel: PlayerViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Tiles rather than label/value rows, as on iOS. These are five unrelated counts, and a
        // column of rows invites reading them as a list where the order means something.
        StatGrid(
            listOf(
                stringResource(R.string.cached_tracks) to state.cachedTracks.size.toString(),
                stringResource(R.string.cache_size) to formatBytes(state.cacheBytes),
                stringResource(R.string.playlists) to
                    "${state.playlists.size} (${state.playlists.sumOf { it.tracks.size }})",
                stringResource(R.string.recently_played) to state.recentSources.size.toString(),
                stringResource(R.string.training_events) to state.trainingEvents.toString(),
            )
        )

        AnalyticsSection(stringResource(R.string.audio_quality)) {
            val analysed = state.analyses.values
            if (analysed.isEmpty()) {
                Note(stringResource(R.string.no_tracks_analyzed_yet))
            } else {
                // Counts every analysis, not only those with a measurable cutoff. Filtering here
                // is what would hide the best files: they are the ones whose spectrum has no wall
                // to find, so they get their own tile rather than vanishing.
                val unmeasured = analysed.count { it.qualityTier == TrackAnalysis.QualityTier.UNKNOWN }
                StatGrid(
                    buildList {
                        add(stringResource(R.string.analyzed) to analysed.size.toString())
                        add(
                            stringResource(R.string.below_15_khz) to
                                analysed.count { it.qualityTier == TrackAnalysis.QualityTier.LOW }.toString()
                        )
                        add(
                            stringResource(R.string._15_18_khz) to
                                analysed.count { it.qualityTier == TrackAnalysis.QualityTier.MEDIUM }.toString()
                        )
                        add(
                            stringResource(R.string.above_18_khz) to
                                analysed.count { it.qualityTier == TrackAnalysis.QualityTier.HIGH }.toString()
                        )
                        if (unmeasured > 0) {
                            add(stringResource(R.string.unmeasured) to unmeasured.toString())
                        }
                    }
                )
                Note(stringResource(R.string.where_each_track_s_spectrum_stops_a_wall_well_below_20_khz_i))
            }

            // A one-off job, not a preference — which is why it lives next to the numbers it
            // fills in rather than in Settings.
            val remaining = state.cachedTracks.count { state.analyses[it.fileId] == null }
            if (remaining > 0) {
                TextButton(onClick = { viewModel.analyzeAll() }) {
                    Text(stringResource(R.string.analyze_all_downloaded_tracks))
                }
            }
        }

        AnalyticsSection(stringResource(R.string.recently_played)) {
            if (state.recentSources.isEmpty()) {
                Note(stringResource(R.string.nothing_played_yet))
            } else {
                state.recentSources.sortedByDescending { it.playCount }.forEach { recent ->
                    StatRow(
                        recent.source.name.ifBlank { stringResource(R.string.library) },
                        "${recent.playCount}×",
                    )
                }
            }
        }

        AnalyticsSection(stringResource(R.string.model_details)) {
            if (state.trainingEvents == 0 || state.featureWeights.isEmpty()) {
                Note(stringResource(R.string.model_not_trained_yet))
            } else {
                StatGrid(
                    listOf(
                        stringResource(R.string.architecture) to state.modelArchitecture,
                        stringResource(R.string.weight_norm) to "%.3f".format(state.modelWeightNorm),
                        stringResource(R.string.min_weight) to "%.3f".format(state.modelMinWeight),
                        stringResource(R.string.max_weight) to "%.3f".format(state.modelMaxWeight),
                    )
                )
                WeightBars(state.featureWeights)
                Note(stringResource(R.string.average_weight_by_feature_group))
            }
        }

        AnalyticsSection(stringResource(R.string.network_activity)) {
            if (state.modelW1.isEmpty()) {
                Note(stringResource(R.string.model_not_trained_yet))
            } else {
                NetworkDiagram(state.modelW1, state.modelW2, state.trainingEvents)
                Note(stringResource(R.string.network_activity_detail))
            }
        }

        AnalyticsSection(stringResource(R.string.recent_training_events)) {
            TrainingEvents(state.modelEvents)
        }
    }
}

/**
 * The model as a diagram: one node per input dimension, the hidden layer, one output.
 *
 * Every input, not one node per feature group. Grouping was my own shortcut and it misrepresented
 * the model — it drew a 6→12→1 network when the thing being described is 47→12→1, and the
 * Architecture tile directly above says so. A diagram that disagrees with the number next to it is
 * worse than no diagram.
 *
 * Edges carry the weight's sign as colour and its magnitude as opacity, so what shows is which
 * connections the model actually leans on; drawn uniformly, every network looks identical whatever
 * it learned.
 *
 * A pulse runs the edges on a training step and is otherwise still. The animation exists to mark
 * that something happened, and movement that never stops marks nothing.
 */
@Composable
private fun NetworkDiagram(w1: List<List<Double>>, w2: List<Double>, trainingEvents: Int) {
    val accent = MaterialTheme.colorScheme.primary
    val negative = MaterialTheme.colorScheme.error
    val idle = MaterialTheme.colorScheme.outline

    val pulse = remember { Animatable(1f) }
    LaunchedEffect(trainingEvents) {
        pulse.snapTo(0f)
        pulse.animateTo(1f, tween(1400))
    }

    // `w1` is hidden-major: w1[hidden][input].
    val inputCount = w1.firstOrNull()?.size ?: 0
    val hiddenCount = w1.size
    val maxIn = w1.flatten().maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0 } ?: 1.0
    val maxOut = w2.maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0 } ?: 1.0

    Canvas(modifier = Modifier.fillMaxWidth().height(NETWORK_HEIGHT)) {
        val inputX = 16.dp.toPx()
        val hiddenX = size.width / 2
        val outputX = size.width - 16.dp.toPx()

        // Evenly spaced with a gap at each end, as iOS lays it out: height / (count + 1).
        fun y(index: Int, count: Int) = size.height * (index + 1f) / (count + 1f)

        for (hidden in 0 until hiddenCount) {
            for (input in 0 until inputCount) {
                val weight = w1[hidden][input]
                val strength = (kotlin.math.abs(weight) / maxIn).toFloat()
                drawLine(
                    color = (if (weight >= 0) accent else negative).copy(alpha = 0.04f + strength * 0.35f),
                    start = Offset(inputX, y(input, inputCount)),
                    end = Offset(hiddenX, y(hidden, hiddenCount)),
                    strokeWidth = 0.7.dp.toPx(),
                )
            }
        }
        for (hidden in 0 until hiddenCount) {
            val strength = (kotlin.math.abs(w2[hidden]) / maxOut).toFloat()
            drawLine(
                color = (if (w2[hidden] >= 0) accent else negative).copy(alpha = 0.1f + strength * 0.6f),
                start = Offset(hiddenX, y(hidden, hiddenCount)),
                end = Offset(outputX, size.height / 2),
                strokeWidth = 1.dp.toPx(),
            )
        }

        if (pulse.value < 1f) {
            val progress = pulse.value
            val fade = (1f - progress) * 0.8f
            // One dot per input, travelling to the nearest hidden unit rather than one per edge:
            // 564 dots at once is a smear, not a pulse.
            for (input in 0 until inputCount) {
                val hidden = input * hiddenCount / inputCount.coerceAtLeast(1)
                val from = Offset(inputX, y(input, inputCount))
                val to = Offset(hiddenX, y(hidden.coerceIn(0, hiddenCount - 1), hiddenCount))
                drawCircle(
                    color = accent.copy(alpha = fade),
                    radius = 1.5.dp.toPx(),
                    center = Offset(
                        from.x + (to.x - from.x) * progress,
                        from.y + (to.y - from.y) * progress,
                    ),
                )
            }
        }

        for (input in 0 until inputCount) {
            drawCircle(idle, radius = 1.5.dp.toPx(), center = Offset(inputX, y(input, inputCount)))
        }
        for (hidden in 0 until hiddenCount) {
            drawCircle(idle, radius = 3.dp.toPx(), center = Offset(hiddenX, y(hidden, hiddenCount)))
        }
        drawCircle(accent, radius = 5.dp.toPx(), center = Offset(outputX, size.height / 2))
    }
}

/** Tall enough that 47 input nodes are separate dots rather than a line. */
private val NETWORK_HEIGHT = 280.dp

/**
 * The training log: what the model predicted, against what actually happened.
 *
 * Paged rather than scrolled, because this sits inside a screen that already scrolls — a list that
 * scrolls within a scroll is a list you cannot reliably reach the bottom of.
 */
@Composable
private fun TrainingEvents(events: List<ModelEvent>) {
    if (events.isEmpty()) {
        Note(stringResource(R.string.no_training_events_yet_play_a_few_tracks_first))
        return
    }

    var page by remember { mutableStateOf(0) }
    val pageCount = ((events.size + EVENTS_PER_PAGE - 1) / EVENTS_PER_PAGE).coerceAtLeast(1)
    val current = page.coerceIn(0, pageCount - 1)
    val shown = events.drop(current * EVENTS_PER_PAGE).take(EVENTS_PER_PAGE)

    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.predicted),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(64.dp),
                textAlign = TextAlign.End,
            )
            Text(
                stringResource(R.string.actual),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(64.dp),
                textAlign = TextAlign.End,
            )
        }
        shown.forEach { event ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Rounded, not truncated: 0.567 is 57%, and truncating would put this list one
                // point below every other place the same number is shown.
                Percent((event.predicted * 100).roundToInt())
                Percent((event.fraction * 100).roundToInt())
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }

        if (pageCount > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { page = current - 1 }, enabled = current > 0) {
                    Text(stringResource(R.string.prev))
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.page_lld_of_lld, current + 1, pageCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { page = current + 1 }, enabled = current < pageCount - 1) {
                    Text(stringResource(R.string.next))
                }
            }
        }
    }
}

@Composable
private fun Percent(value: Int) {
    Text(
        "$value%",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.End,
        modifier = Modifier.width(64.dp),
    )
}

private const val EVENTS_PER_PAGE = 8

/** Two-column tiles. */
@Composable
private fun StatGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    StatTile(label, value, modifier = Modifier.weight(1f))
                }
                // Keeps a lone tile on the last row at half width rather than letting it stretch
                // across and read as a different kind of thing.
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun AnalyticsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * Weight magnitude per feature group, as bars.
 *
 * Scaled against the largest group rather than against an absolute, because the numbers themselves
 * mean nothing to a reader — what is legible is which groups the model leans on relative to the
 * others, and that is a comparison the bars make directly.
 */
@Composable
private fun WeightBars(weights: List<Pair<String, Double>>) {
    val maximum = weights.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        weights.forEach { (label, magnitude) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(96.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((magnitude / maximum).toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
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
