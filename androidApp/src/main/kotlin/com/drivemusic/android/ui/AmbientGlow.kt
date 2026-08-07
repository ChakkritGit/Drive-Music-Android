package com.drivemusic.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * The ambient backdrop behind Now Playing's cover — the artwork's average colour, breathing slowly
 * and swelling with the music. Mirrors `NowPlayingView.glow`.
 *
 * A radial gradient rather than a blurred rectangle. Blur is an offscreen pass over everything it
 * covers, and this covers the whole screen; inside a per-frame loop that is a full-screen blur
 * every frame for a result a gradient gives free. Blurring a *solid* colour was never doing any
 * work anyway — all it softened was the rectangle's own edges.
 */
@Composable
fun AmbientGlow(
    artwork: ByteArray?,
    level: () -> Float,
    modifier: Modifier = Modifier,
) {
    // No artwork, no glow. A missing cover used to fall through to a transparent colour, which at
    // 55% alpha is black — so a track without a cover washed the whole screen grey.
    val target = rememberAverageColor(artwork) ?: return
    // Crossfaded rather than swapped: the colour changes when the track does, and a hard cut
    // between two full-screen washes reads as the screen flashing.
    val color by animateColorAsState(target, tween(700), label = "glow")

    val frame by produceState(0L) {
        while (true) {
            withInfiniteAnimationFrameMillis { value = it }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val seconds = frame / 1000.0
        // A 30-second cycle: slow enough to read as breathing rather than as pulsing, which is
        // what a rhythm anywhere near the music's own would look like next to it.
        val breathe = 1 + 0.05 * sin(seconds / 30 * 2 * PI)
        val loudness = level().coerceIn(0f, 1f)
        val radius = (size.minDimension * 0.9f * (breathe * (1 + loudness * 0.18)).toFloat())

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.55f),
                    color.copy(alpha = 0.18f),
                    Color.Transparent,
                ),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
            alpha = 0.65f + loudness * 0.35f,
        )
    }
}

/**
 * The cover again, spread wider and blurred to a wash behind itself — a coloured shadow cast from
 * the artwork's own palette. Mirrors `NowPlayingView.artworkHalo`.
 *
 * Only drawn when the glow is off. With the glow on, the glow already provides exactly this and
 * the two stack into mud; with it off there is nothing behind the cover at all and it sits flat on
 * the background.
 *
 * Blurred by drawing a handful of pixels across the whole area and letting the GPU interpolate,
 * rather than by a blur pass. `Modifier.blur` needs API 31 and this app runs from 26, and a real
 * blur over a large bitmap is expensive for something whose entire purpose is to be indistinct.
 *
 * The size is fixed at [HALO_PIXELS] rather than left to `inSampleSize`, which is relative to the
 * source: a large cover sampled down by the same factor still keeps enough structure to be
 * recognisable, and a recognisable copy sitting behind the original reads as a misprint rather
 * than as light. Six pixels across cannot resemble anything.
 *
 * Centred, and with no rounded corner. Both were wrong for the same reason: a hard edge and an
 * offset are how you notice that something is a *copy* of the cover. Light has neither.
 */
@Composable
fun ArtworkHalo(artwork: ByteArray?, size: Dp, modifier: Modifier = Modifier) {
    val thumbnail = rememberThumbnail(artwork) ?: return

    Image(
        bitmap = thumbnail.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        // Bilinear, so the six pixels become a gradient rather than six squares.
        filterQuality = FilterQuality.High,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                // Spread past the cover on every side, so what shows is only the spill.
                scaleX = HALO_SPREAD
                scaleY = HALO_SPREAD
                alpha = 0.85f
                // The fade below multiplies this layer's own alpha, which it needs a layer to
                // multiply into.
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                // Faded to nothing at its edges. Blurring the colours was only half of it: a
                // rectangle of soft colour is still a rectangle, and its boundary is exactly what
                // gives away that there is an image back there rather than light.
                drawRect(
                    Brush.radialGradient(
                        0.45f to Color.Black,
                        1f to Color.Transparent,
                        center = center,
                        radius = size.toPx() / 2,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

/**
 * The artwork's average colour, computed once per track from a tiny thumbnail.
 *
 * Averaged from a 1×1 downscale rather than by walking pixels: the decoder does the reduction
 * while it reads the file, so the whole thing is one decode of a few bytes instead of a loop over
 * a megabyte of bitmap on the main thread — which is exactly how this ended up janking track
 * changes on iOS before it was moved off the render path there too.
 */
@Composable
private fun rememberAverageColor(artwork: ByteArray?): Color? = remember(artwork) {
    val bytes = artwork ?: return@remember null
    val bitmap = runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampledOptions(1))
    }.getOrNull() ?: return@remember null
    val scaled = runCatching { Bitmap.createScaledBitmap(bitmap, 1, 1, true) }.getOrNull()
    val pixel = scaled?.getPixel(0, 0) ?: return@remember null
    scaled.recycle()
    Color(pixel).copy(alpha = 1f)
}

/**
 * The cover reduced to [HALO_PIXELS] square — see [ArtworkHalo].
 *
 * Sampled down by the decoder first so the full-size bitmap is never allocated, then scaled to an
 * exact size, because `inSampleSize` is a ratio and this needs an absolute answer.
 */
@Composable
private fun rememberThumbnail(artwork: ByteArray?): Bitmap? = remember(artwork) {
    val bytes = artwork ?: return@remember null
    val sampled = runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampledOptions(HALO_PIXELS))
    }.getOrNull() ?: return@remember null
    runCatching { Bitmap.createScaledBitmap(sampled, HALO_PIXELS, HALO_PIXELS, true) }
        .getOrNull()
        .also { if (it !== sampled) sampled.recycle() }
}

private fun sampledOptions(target: Int) = BitmapFactory.Options().apply {
    // Decoded at a fraction of full size. `inSampleSize` is honoured by the decoder itself, so the
    // full-size bitmap is never allocated at all.
    // A ratio, not a size: it only has to get the decode down to something small before the exact
    // scaling above. 32 turns any realistic cover into tens of pixels.
    inSampleSize = 32
    inPreferredConfig = Bitmap.Config.ARGB_8888
    inScaled = false
    if (target <= 1) inSampleSize = 64
}

/**
 * How many pixels wide the halo's source is. Small enough that nothing in the cover survives as a
 * shape — only its colours, in roughly the places they were.
 */
private const val HALO_PIXELS = 6

/** How far past the cover the halo spreads. */
private const val HALO_SPREAD = 1.35f
