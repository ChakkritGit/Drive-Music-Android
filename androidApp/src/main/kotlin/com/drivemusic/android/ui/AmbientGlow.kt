package com.drivemusic.android.ui

import android.graphics.Bitmap
import android.os.Build
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
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
                // The last stop is the same colour at zero alpha, not `Color.Transparent` —
                // which is transparent *black*, so the wash graded through grey before it
                // disappeared instead of simply thinning out.
                colors = listOf(
                    color.copy(alpha = 0.55f),
                    color.copy(alpha = 0.18f),
                    color.copy(alpha = 0f),
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
 * Blurred properly where the platform can — `Modifier.blur` with an unbounded edge, which is what
 * makes the light spill past its own bounds instead of stopping at them. That needs API 31, and
 * this app runs from 26, so below that the blur is done by drawing a handful of pixels across the
 * whole area and letting the GPU interpolate: cruder, but the same idea and free.
 *
 * The source is reduced to [HALO_PIXELS] either way. `inSampleSize` alone was not enough — it is a
 * ratio, so a large cover reduced by the same factor still keeps enough structure to be
 * recognisable, and a recognisable copy behind the original reads as a misprint rather than as
 * light. Six pixels across cannot resemble anything.
 *
 * Centred, with no rounded corner. Both were how you noticed it was a *copy* of the cover sitting
 * behind it; light has neither.
 *
 * Sized by the caller to the cover's own box, with the spread coming entirely from the blur. An
 * earlier version laid this out larger than the cover to get the spread, which made it claim that
 * space in the layout and push everything below it down the screen. A decoration behind something
 * should occupy nothing.
 */
@Composable
fun ArtworkHalo(artwork: ByteArray?, modifier: Modifier = Modifier) {
    val thumbnail = rememberThumbnail(artwork) ?: return

    Image(
        bitmap = thumbnail.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        // Bilinear, so the few pixels become a gradient rather than a grid of squares.
        filterQuality = FilterQuality.High,
        modifier = modifier
            .then(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Unbounded, so the blur may bleed past the image's own box. The bounded
                    // default clips the falloff flush against the edge, which is exactly the hard
                    // rectangle this exists to avoid.
                    Modifier.blur(HALO_BLUR, BlurredEdgeTreatment.Unbounded)
                } else {
                    Modifier
                }
            )
            .alpha(0.9f),
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

/**
 * Matches the 60pt the iOS halo uses. Large relative to the cover on purpose: the blur *is* the
 * spread here, since nothing scales the image up any more.
 */
private val HALO_BLUR = 64.dp


