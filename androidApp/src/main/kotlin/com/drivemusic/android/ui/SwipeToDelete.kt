package com.drivemusic.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.drivemusic.android.R
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A row that slides aside to reveal a delete button.
 *
 * The button used to sit permanently in the row, a thumb's width from the row's own tap target and
 * one tap from destroying a playlist with no confirmation — which is exactly how a playlist gets
 * destroyed. Hiding it behind a deliberate sideways drag means the gesture that deletes cannot be
 * the gesture that opens, and the dialog means neither of them deletes on its own.
 *
 * Two ways in, because both are things people already try: drag a little and press the revealed
 * button, or drag decisively across and let go. Both end at the same dialog rather than one of
 * them being a shortcut past it — a long swipe is a statement of intent, not of certainty, and the
 * cost of being wrong here is a list you cannot get back.
 */
@Composable
fun SwipeToDeleteRow(
    onDeleteRequested: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { REVEAL_WIDTH.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableFloatStateOf(0f) }

    Box(modifier = modifier.fillMaxWidth().onSizeChanged { widthPx = it.width.toFloat() }) {
        // The button lives behind the row and is only reachable once the row has moved off it, so
        // it cannot be hit by a tap aimed at the row.
        Box(
            // Sized from the row rather than to a guessed height, so the panel is always exactly
            // as tall as the thing that slid off it.
            modifier = Modifier.matchParentSize(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(REVEAL_WIDTH)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = {
                    onDeleteRequested()
                    scope.launch { offset.animateTo(0f) }
                }) {
                    Icon(
                        painterResource(AppIcons.Delete),
                        contentDescription = stringResource(R.string.delete_playlist),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                // Opaque, or the button behind shows through the row that is covering it.
                .background(MaterialTheme.colorScheme.surface)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            // Rightward drag past the resting position does nothing: there is
                            // nothing revealed on that side to drag towards.
                            offset.snapTo((offset.value + delta).coerceIn(-widthPx, 0f))
                        }
                    },
                    onDragStopped = {
                        val settled = offset.value
                        scope.launch {
                            when {
                                settled < -widthPx * FULL_SWIPE_FRACTION -> {
                                    offset.animateTo(0f)
                                    onDeleteRequested()
                                }
                                // Half the button's width is enough intent to leave it showing;
                                // anything less reads as a scroll that wandered.
                                settled < -revealPx / 2 -> offset.animateTo(-revealPx)
                                else -> offset.animateTo(0f)
                            }
                        }
                    },
                ),
        ) {
            content()
        }
    }
}

/** How much of the row's width must be crossed for a drag to count as "delete this". */
private const val FULL_SWIPE_FRACTION = 0.45f

private val REVEAL_WIDTH = 72.dp
