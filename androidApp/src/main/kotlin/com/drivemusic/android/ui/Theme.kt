package com.drivemusic.android.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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

    // The system bars are transparent under edge-to-edge, so what has to follow the theme is
    // their *icon* colour. `enableEdgeToEdge()` sets this once from the device's dark-mode
    // setting, which is the wrong source the moment the app carries its own override: pinning the
    // app to Dark on a light device left black icons on a black bar, and invisible.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(colorScheme = colors, content = content)
}

private val Accent = Color(0xFF6B4FBB)
private val AccentMuted = Color(0xFF8A79C7)
