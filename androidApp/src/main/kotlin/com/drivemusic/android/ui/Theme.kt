package com.drivemusic.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.drivemusic.android.player.AppTheme

/**
 * The app's colours, honouring the in-app Appearance override.
 *
 * The override is the point: [AppTheme.LIGHT] and [AppTheme.DARK] pin the app regardless of the
 * device setting, which is what the setting promises. Before this the choice was stored and then
 * ignored — the picker moved and nothing happened.
 *
 * Dynamic colour on Android 12+ so the app takes the wallpaper palette like the rest of the
 * system, with a fixed purple scheme underneath it for everything older.
 */
@Composable
fun DriveMusicTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val dark = when (theme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }
    val context = LocalContext.current

    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Accent, secondary = AccentMuted)
        else -> lightColorScheme(primary = Accent, secondary = AccentMuted)
    }

    MaterialTheme(colorScheme = colors, content = content)
}

private val Accent = Color(0xFF6B4FBB)
private val AccentMuted = Color(0xFF8A79C7)
