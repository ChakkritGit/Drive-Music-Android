package com.drivemusic.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A collection's cover: one image if that is all there is, a 2×2 mosaic when there are four.
 *
 * Matches the iOS shelves. The point of the mosaic is that a folder or playlist has no artwork of
 * its own — showing the first track's cover alone would read as "this *is* that track", while four
 * reads as "a set of things".
 */
@Composable
fun ArtworkCollage(
    covers: List<ByteArray>,
    size: Dp,
    cornerRadius: Dp = 12.dp,
    fallbackIcon: ImageVector = Icons.Default.MusicNote,
) {
    val bitmaps = remember(covers) {
        covers.take(4).mapNotNull {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
    }

    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmaps.isEmpty() -> Icon(
                fallbackIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(size / 3),
            )

            bitmaps.size < 4 -> Image(
                bitmap = bitmaps.first().asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            else -> Column(modifier = Modifier.fillMaxSize()) {
                repeat(2) { row ->
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        repeat(2) { column ->
                            Image(
                                bitmap = bitmaps[row * 2 + column].asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.weight(1f).fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A shelf card: collage, title, subtitle. 160dp wide, matching iOS. */
@Composable
fun CollectionCard(
    title: String,
    subtitle: String,
    covers: List<ByteArray>,
    fallbackIcon: ImageVector = Icons.Default.MusicNote,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(CARD_SIZE).clickable(onClick = onClick),
    ) {
        ArtworkCollage(covers, size = CARD_SIZE, fallbackIcon = fallbackIcon)
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val CARD_SIZE = 160.dp

/** The single search field used by every list screen. */
@Composable
fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        singleLine = true,
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
