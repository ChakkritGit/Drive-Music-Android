package com.drivemusic.android.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The neon halo behind the play button while the mix engine is running — the same "this is doing
 * something extra" signal the Mixing badge gives, in the one place the user is already looking.
 * Mirrors `NowPlayingView.mixGlow`.
 *
 * Three stacked circles rather than one: a single soft ring reads as a shadow, while a tight
 * bright core with progressively wider, dimmer rings around it is what actually looks like emitted
 * light.
 *
 * Brightness is constant. An earlier version of the iOS one pulsed and read as a flicker — a real
 * neon tube either glows steadily or it is broken — so the movement below is what carries the
 * sense of something running.
 *
 * Driven by wall-clock frame time rather than a repeating animation, for the same reason the
 * marquee is: this sits inside a screen that rebuilds several times a second from the progress
 * tick, and a repeating animation restarts from scratch on every rebuild, which shows up as the
 * motion stuttering.
 */
@Composable
fun MixGlow(scale: Float, alpha: Float, modifier: Modifier = Modifier) {
    val frame by produceState(0L) {
        while (true) {
            withInfiniteAnimationFrameMillis { value = it }
        }
    }
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary

    // Sized to whatever the caller gives it — the button — and drawing well outside those bounds.
    // Sizing it to the glow instead made the Box around the play button 128dp tall, so the whole
    // transport row grew the moment playback started and every control shifted down with it.
    // Nothing clips here, so drawing past the edge costs nothing but is invisible to layout.
    //
    // [scale] and [alpha] are applied to what is drawn rather than through a `graphicsLayer`. A
    // layer is rasterised at the node's own size, and everything here is deliberately drawn well
    // outside it — so animating one cut the strands off square at the button's edge and the retract
    // read as the light shattering rather than sliding away.
    Canvas(modifier = modifier) {
        val time = frame / 1000.0

        // The soft light the strands throw. Drawn as radial gradients rather than blurred solid
        // circles: a blur is an offscreen pass per frame for a falloff a gradient describes
        // directly.
        haze(accent, 0.5f * alpha, 78.dp.toPx() / 2 * scale)
        haze(accent, 0.3f * alpha, 92.dp.toPx() / 2 * scale)
        haze(accent, 0.18f * alpha, 112.dp.toPx() / 2 * scale)

        // Two strands, each with its own wave speeds and directions. Nothing is shared between
        // them but the circle they travel on, so they drift together and apart on their own
        // schedules instead of holding a fixed braid that merely spins.
        strand(accent, time, 1 / 7.0, -1 / 11.0, 0.0, scale, alpha)
        strand(accent, time, -1 / 9.0, 1 / 13.0, PI, scale, alpha)
    }
}

private fun DrawScope.haze(color: Color, alpha: Float, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * One neon strand, drawn three times: a wide soft pass for the light it throws, a medium one, and
 * a thin sharp line for the filament itself — the build-up a real neon tube has, and the reason a
 * single stroked line never looks lit.
 *
 * The speeds are deliberately awkward fractions of a turn per second, running in opposing
 * directions. Nothing here divides evenly into anything else, so the two waves within a strand —
 * and the two strands against each other — never return to the same arrangement; the movement
 * reads as a slow, aimless crawl rather than as a loop.
 */
private fun DrawScope.strand(
    color: Color,
    time: Double,
    primarySpeed: Double,
    secondarySpeed: Double,
    offset: Double,
    scale: Float,
    alpha: Float,
) {
    val path = helixPath(
        primaryPhase = time * primarySpeed * 2 * PI + offset,
        secondaryPhase = time * secondarySpeed * 2 * PI + offset,
        radius = STRAND_RADIUS.dp.toPx() * scale,
    )
    drawPath(path, color.copy(alpha = 0.22f * alpha), style = Stroke(width = 7.dp.toPx()))
    drawPath(path, color.copy(alpha = 0.5f * alpha), style = Stroke(width = 2.5.dp.toPx()))
    drawPath(path, color.copy(alpha = 0.95f * alpha), style = Stroke(width = 1.dp.toPx()))
}

/**
 * A circle whose radius is modulated by two sine waves of different frequencies.
 *
 * The amplitudes are small on purpose: their sum is what bounds the strand, and it has to read as
 * movement without colliding with the button inside it or wandering outside the light around it.
 */
private fun DrawScope.helixPath(
    primaryPhase: Double,
    secondaryPhase: Double,
    radius: Float,
): Path {
    val path = Path()
    val cx = center.x
    val cy = center.y
    // The requested radius, not one clamped to the canvas: the canvas is the play button's own
    // box and the strands are meant to travel outside it. Clamping put them inside the button.
    val base = radius

    // 2° steps: fine enough to read as smooth at this diameter, coarse enough to stay cheap to
    // rebuild every frame.
    var angle = 0.0
    while (angle <= 360.0) {
        val radians = angle * PI / 180
        val r = base +
            base * PRIMARY_AMPLITUDE * sin(PRIMARY_LOBES * radians + primaryPhase).toFloat() +
            base * SECONDARY_AMPLITUDE * sin(SECONDARY_LOBES * radians + secondaryPhase).toFloat()
        val point = Offset(
            x = cx + r * cos(radians).toFloat(),
            y = cy + r * sin(radians).toFloat(),
        )
        if (angle == 0.0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        angle += 2.0
    }
    path.close()
    return path
}

/** Sits outside the play button's own 72dp circle, so the strands read as light around it. */
private const val STRAND_RADIUS = 48f

private const val PRIMARY_LOBES = 5.0
private const val SECONDARY_LOBES = 3.0
private const val PRIMARY_AMPLITUDE = 0.075f
private const val SECONDARY_AMPLITUDE = 0.045f
