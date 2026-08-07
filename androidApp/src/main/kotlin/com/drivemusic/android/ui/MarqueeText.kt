package com.drivemusic.android.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * A single-line title that scrolls left until its tail is visible, pauses, scrolls back, and
 * pauses again — the marquee `MarqueeText.swift` implements, which is Apple Music's.
 *
 * Not Compose's own `basicMarquee`, which was here first and is a different animation: it scrolls
 * one way forever, wrapping the head of the string around behind its tail with a gap. That reads
 * as a ticker — text with no beginning that never settles — where this is a title that shows you
 * its start, shows you its end, and comes back. The pauses at each end are the point; they are
 * when the title is actually readable.
 *
 * Only animates when the text does not fit. A title that fits renders as ordinary static text: a
 * marquee on a string with nothing hidden is motion for its own sake.
 *
 * Driven by wall-clock frame time, not by a `State`-driven animation. The mini player is one of
 * this view's hosts and is rebuilt on every playback state change and every tab switch; a
 * state-driven animation restarts from the top each time, so the title would snap back to its head
 * whenever anything else on screen moved. A position computed from the clock cannot restart —
 * whichever instance renders "now" computes what "now" already looks like.
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    isActive: Boolean = true,
) {
    val density = LocalDensity.current
    var containerWidth by remember { mutableIntStateOf(0) }
    var textWidth by remember { mutableIntStateOf(0) }

    // Both widths come from the laid-out nodes themselves rather than from a separate
    // `TextMeasurer` pass. A measurement taken outside the layout does not necessarily agree with
    // what the layout produces — it disagreed here by about 60dp, and the text stopped that far
    // short of the trailing edge with a gap where its tail should have been. Reading the real
    // width makes the two agree by construction.
    val overflow = max(0, textWidth - containerWidth)
    val scrolls = isActive && overflow > 1 && containerWidth > 0

    val pixelsPerSecond = with(density) { SCROLL_DP_PER_SECOND.dp.toPx() }
    val fadeWidth = with(density) { FADE_WIDTH.dp.toPx() }
    val scrollSeconds = max(2f, overflow / pixelsPerSecond)
    val cycleMillis = ((2 * PAUSE_SECONDS + 2 * scrollSeconds) * 1000).toLong()

    val progress by produceState(0f, cycleMillis, scrolls) {
        if (!scrolls) {
            value = 0f
            return@produceState
        }
        while (true) {
            withInfiniteAnimationFrameMillis { frameMillis ->
                value = progressAt(frameMillis.mod(cycleMillis), scrollSeconds)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Clipped: the text is laid out at its full intrinsic width and then translated, so
            // without this it draws straight past the container's edges — over the favourite
            // button on one side and off the screen on the other.
            .clipToBounds()
            .then(
                // The fade belongs to the *window*, not to the text moving through it. Drawn on
                // the text it was sized to the full string and slid along with it, so the soft
                // edges travelled with the words instead of staying at the container's edges,
                // which is the visible thing that was wrong.
                if (scrolls) {
                    Modifier
                        .graphicsLayer {
                            // The gradient below multiplies alpha, which needs a layer of its own
                            // to multiply into — without one it would cut a hole through
                            // everything drawn underneath as well.
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                edgeFade(progress, size.width, fadeWidth),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                } else {
                    Modifier
                }
            )
            .onSizeChanged { containerWidth = it.width },
    ) {
        if (scrolls) {
            Text(
                text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                // Laid out at its own full width, ignoring the container's constraints — SwiftUI
                // spells this `.fixedSize()`. Without it the text is measured against the
                // container and simply ends there, so scrolling left revealed blank space where
                // the tail should have been: the last part of the title was never laid out.
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .onSizeChanged { textWidth = it.width }
                    .graphicsLayer { translationX = -overflow * progress },
            )
        } else {
            // Laid out unbounded too, so its real width is known before it is known whether it
            // overflows — otherwise a title that needs to scroll would first be truncated, and a
            // truncated string always "fits".
            Text(
                text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .onSizeChanged { textWidth = it.width },
            )
        }
    }
}

/**
 * Fades whichever edge currently has text hidden behind it, in proportion to how much.
 *
 * Showing the head, only the trailing edge fades — that is where the rest of the title continues.
 * Scrolled to the tail, only the leading edge does. A hard clip at either edge reads as text cut
 * off by accident; a fade reads as the window it is travelling through.
 */
private fun edgeFade(progress: Float, width: Float, fadeWidth: Float): Brush {
    val fraction = min(0.4f, fadeWidth / width)
    // Ramped over the first and last tenth of the travel, so neither edge pops on or off.
    val leading = (progress / 0.1f).coerceIn(0f, 1f)
    val trailing = ((1f - progress) / 0.1f).coerceIn(0f, 1f)
    return Brush.horizontalGradient(
        0f to Color.Black.copy(alpha = 1f - leading),
        fraction to Color.Black,
        (1f - fraction) to Color.Black,
        1f to Color.Black.copy(alpha = 1f - trailing),
    )
}

/**
 * 0 showing the title's head, 1 showing its tail: pause, scroll out, pause, scroll back.
 *
 * Never leaves `0..1`, so the text never scrolls past either of its own ends into empty space.
 */
private fun progressAt(elapsedMillis: Long, scrollSeconds: Float): Float {
    val elapsed = elapsedMillis / 1000f
    val scrollOutEnd = PAUSE_SECONDS + scrollSeconds
    val scrollBackStart = scrollOutEnd + PAUSE_SECONDS
    return when {
        elapsed < PAUSE_SECONDS -> 0f
        elapsed < scrollOutEnd -> (elapsed - PAUSE_SECONDS) / scrollSeconds
        elapsed < scrollBackStart -> 1f
        else -> (1f - (elapsed - scrollBackStart) / scrollSeconds).coerceAtLeast(0f)
    }
}

/** How long the title sits still at each end, fully readable, before moving again. */
private const val PAUSE_SECONDS = 1.2f

/**
 * Scroll speed. A leg's duration scales with how far it has to travel, so a barely-overflowing
 * title does not take as long to cross as a wildly long one.
 */
private const val SCROLL_DP_PER_SECOND = 28f

private const val FADE_WIDTH = 12f
