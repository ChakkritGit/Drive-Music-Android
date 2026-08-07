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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
 * The cover again, scaled up and heavily blurred behind itself — a coloured shadow cast from the
 * artwork's own palette. Mirrors `NowPlayingView.artworkHalo`.
 *
 * Only drawn when the glow is off. With the glow on, the glow already provides exactly this and
 * the two stack into mud; with it off there is nothing behind the cover at all and it sits flat on
 * the background.
 *
 * The blur is done by decoding the cover very small and letting the GPU stretch it back up, rather
 * than with `Modifier.blur` — that requires API 31 and this app runs from 26, and a real blur pass
 * over a large bitmap every frame is expensive for something whose whole purpose is to be
 * indistinct. A 24px thumbnail scaled up 20× is a blur, and it costs one bilinear filter.
 */
@Composable
fun ArtworkHalo(artwork: ByteArray?, size: Dp, modifier: Modifier = Modifier) {
    val thumbnail = rememberThumbnail(artwork) ?: return

    Image(
        bitmap = thumbnail.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.High,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                // Spread wider and pushed down, so the light reads as coming from *behind* the
                // cover rather than as a fuzzy copy of it peeking out evenly on all sides.
                scaleX = 1.22f
                scaleY = 1.22f
                translationY = 26.dp.toPx()
                alpha = 0.95f
            }
            .clip(RoundedCornerShape(10.dp)),
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

/** A deliberately tiny decode of the cover — see [ArtworkHalo]. */
@Composable
private fun rememberThumbnail(artwork: ByteArray?): Bitmap? = remember(artwork) {
    val bytes = artwork ?: return@remember null
    runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampledOptions(HALO_SIZE))
    }.getOrNull()
}

private fun sampledOptions(target: Int) = BitmapFactory.Options().apply {
    // Decoded at a fraction of full size. `inSampleSize` is honoured by the decoder itself, so the
    // full-size bitmap is never allocated at all.
    inSampleSize = 32
    inPreferredConfig = Bitmap.Config.ARGB_8888
    inScaled = false
    if (target <= 1) inSampleSize = 64
}

private const val HALO_SIZE = 24
