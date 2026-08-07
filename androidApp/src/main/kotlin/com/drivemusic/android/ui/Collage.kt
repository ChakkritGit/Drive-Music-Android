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
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
    @androidx.annotation.DrawableRes fallbackIcon: Int = AppIcons.MusicNote,
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
painterResource(fallbackIcon),
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
    @androidx.annotation.DrawableRes fallbackIcon: Int = AppIcons.MusicNote,
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

/**
 * The one search control's shape: a full-width pill on a raised container.
 *
 * Both the button and the field wear it, so pressing Home's search button does not hand you a
 * differently-shaped box — the control you tapped is the control you end up typing in, and it
 * simply grows a cursor. An outlined field would announce itself as a different widget.
 */
private val SEARCH_SHAPE = RoundedCornerShape(28.dp)

/** The pill's height. Shorter than a stock `TextField`'s 56dp, which is sized for a floating
 * label this control does not have — the placeholder is the label, and the spare vertical space
 * just pushed the results further down the screen. */
private val SEARCH_HEIGHT = 44.dp

/**
 * The single search field used by every list screen.
 *
 * Built from a [BasicTextField] inside the same pill [SearchButton] draws, rather than from a
 * Material `TextField`: `TextField` enforces its own minimum height and internal padding, which is
 * what made the two controls different sizes and the bar taller than it needed to be.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 16.dp,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions =
        androidx.compose.foundation.text.KeyboardActions.Default,
    onChange: (String) -> Unit,
) {
    androidx.compose.material3.Surface(
        shape = SEARCH_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().height(SEARCH_HEIGHT).padding(horizontal = contentPadding),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(AppIcons.Search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge
                    .copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                decorationBox = { inner ->
                    // `BasicTextField` has no placeholder of its own, so it is drawn behind the
                    // text and hidden once there is any.
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

/**
 * A search field that is not one yet: it wears exactly the same pill as [SearchField] but only
 * navigates.
 *
 * Used where searching means leaving for a screen of its own, so the affordance is honest about
 * being a button while still promising the thing it leads to.
 */
@Composable
fun SearchButton(placeholder: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = SEARCH_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().height(SEARCH_HEIGHT),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(AppIcons.Search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/**
 * A titled settings section — the Material 3 answer to a SwiftUI `List` section header.
 *
 * Settings screens read as one undifferentiated column of switches without these; the grouping is
 * what tells you that "Length" belongs to "Crossfade" and not to the thing above it.
 */
@Composable
fun SettingsSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** The card a section's rows sit inside, so a group reads as one block. */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        androidx.compose.foundation.layout.Column(content = content)
    }
}

/**
 * The rule between rows in a settings group.
 *
 * Inset on both sides rather than edge to edge: starting it under the icon cuts the row's own
 * leading column in half, and running it into the card's corner radius makes the card look like it
 * has been sliced. [startInset] defaults to where text sits in a row that has an icon — 16dp of
 * padding, a 24dp icon, 16dp of spacing.
 */
@Composable
fun SettingsDivider(startInset: Dp = 56.dp) {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = startInset, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
