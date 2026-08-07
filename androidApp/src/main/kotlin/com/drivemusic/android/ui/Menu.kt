package com.drivemusic.android.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * The app's menu, styled to the Material 3 spec rather than to Compose's defaults.
 *
 * Three things the default gets wrong for this app's surfaces: the container is `surface`, which
 * on a dark theme is the same value as the screen behind it so the menu reads as a floating
 * rectangle with no edge; the corner radius is 4dp, far tighter than the spec's menu container;
 * and there is no tonal separation from what it covers. This sets a raised container, a rounded
 * shape, and real elevation, so a menu looks like a sheet laid over the screen.
 *
 * Icons go at the *end* of an item, not the start — that is where the spec puts them, and it
 * keeps the labels' left edges aligned so the list is read as a column of choices.
 */
@Composable
fun AppMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        shape = RoundedCornerShape(CORNER),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        content = content,
    )
}

/** One item: a label, and an optional trailing icon. */
@Composable
fun AppMenuItem(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        onClick = onClick,
        trailingIcon = icon?.let { { Icon(it, contentDescription = null, tint = tint) } },
        contentPadding = ITEM_PADDING,
        colors = MenuDefaults.itemColors(),
    )
}

private val CORNER = 12.dp

/** Wider than the default, so the label and its trailing icon are not crowded together. */
private val ITEM_PADDING = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
