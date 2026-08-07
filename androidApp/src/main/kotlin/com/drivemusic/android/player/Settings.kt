package com.drivemusic.android.player

import android.content.Context
import com.drivemusic.android.audio.EqSettings

/**
 * The playback settings, backed by SharedPreferences.
 *
 * Key names match the iOS app's `UserDefaults` keys, which are themselves carried over from the
 * web version's `localStorage`. Nothing syncs between the three, so this is documentation rather
 * than interop — but it makes "is this the same setting?" answerable by grepping.
 */
class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("drive-music-settings", Context.MODE_PRIVATE)

    var crossfadeEnabled: Boolean
        get() = preferences.getBoolean(CROSSFADE_ENABLED, true)
        set(value) = preferences.edit().putBoolean(CROSSFADE_ENABLED, value).apply()

    var crossfadeSeconds: Double
        get() = preferences.getFloat(CROSSFADE_SECONDS, 8f).toDouble()
        set(value) = preferences.edit().putFloat(CROSSFADE_SECONDS, value.toFloat()).apply()

    var autoMixEnabled: Boolean
        get() = preferences.getBoolean(AUTO_MIX, true)
        set(value) = preferences.edit().putBoolean(AUTO_MIX, value).apply()

    var gaplessEnabled: Boolean
        get() = preferences.getBoolean(GAPLESS, true)
        set(value) = preferences.edit().putBoolean(GAPLESS, value).apply()

    var volumeNormalizationEnabled: Boolean
        get() = preferences.getBoolean(NORMALIZE, true)
        set(value) = preferences.edit().putBoolean(NORMALIZE, value).apply()

    var eq: EqSettings
        get() = EqSettings(
            enabled = preferences.getBoolean(EQ_ENABLED, false),
            bassDb = preferences.getFloat(EQ_BASS, 0f).toDouble(),
            midDb = preferences.getFloat(EQ_MID, 0f).toDouble(),
            trebleDb = preferences.getFloat(EQ_TREBLE, 0f).toDouble(),
        )
        set(value) = preferences.edit()
            .putBoolean(EQ_ENABLED, value.enabled)
            .putFloat(EQ_BASS, value.bassDb.toFloat())
            .putFloat(EQ_MID, value.midDb.toFloat())
            .putFloat(EQ_TREBLE, value.trebleDb.toFloat())
            .apply()

    private companion object {
        const val CROSSFADE_ENABLED = "drive-music-crossfade-enabled"
        const val CROSSFADE_SECONDS = "drive-music-crossfade-seconds"
        const val AUTO_MIX = "drive-music-auto-mix-enabled"
        const val GAPLESS = "drive-music-gapless-enabled"
        const val NORMALIZE = "drive-music-volume-normalization-enabled"
        const val EQ_ENABLED = "drive-music-eq-enabled"
        const val EQ_BASS = "drive-music-eq-bass"
        const val EQ_MID = "drive-music-eq-mid"
        const val EQ_TREBLE = "drive-music-eq-treble"
    }
}

/** How a track list is ordered. Mirrors the iOS sort menu. */
enum class TrackSort(val label: String) {
    NAME("Name"),
    RECENTLY_ADDED("Recently added"),
    ARTIST("Artist"),
    ALBUM("Album");
}
