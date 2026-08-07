package com.drivemusic.android.player

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The in-app appearance and language overrides, independent of the device settings — so the app
 * can be pinned to Light, or to Thai, without touching system Settings. Mirrors the iOS
 * `AppTheme`/`AppLanguage` pair, including the "system means no override" case.
 */
enum class AppTheme(val title: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");

    companion object { const val STORAGE_KEY = "drive-music-app-theme" }
}

enum class AppLanguage(val tag: String?, val nativeName: String) {
    // Each language's own name written in itself rather than translated — the whole point of the
    // label is to stay legible to someone who cannot currently read the interface.
    SYSTEM(null, "System"),
    EN("en", "English"),
    TH("th", "ไทย"),
    JA("ja", "日本語");

    companion object { const val STORAGE_KEY = "drive-music-app-language" }
}

class AppearanceStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("drive-music-settings", Context.MODE_PRIVATE)

    var theme: AppTheme
        get() = runCatching {
            AppTheme.valueOf(preferences.getString(AppTheme.STORAGE_KEY, null) ?: "SYSTEM")
        }.getOrDefault(AppTheme.SYSTEM)
        set(value) = preferences.edit().putString(AppTheme.STORAGE_KEY, value.name).apply()

    var language: AppLanguage
        get() = runCatching {
            AppLanguage.valueOf(preferences.getString(AppLanguage.STORAGE_KEY, null) ?: "SYSTEM")
        }.getOrDefault(AppLanguage.SYSTEM)
        set(value) {
            preferences.edit().putString(AppLanguage.STORAGE_KEY, value.name).apply()
            apply(value)
        }

    /**
     * Applies a language to the running app.
     *
     * `AppCompatDelegate.setApplicationLocales` rather than swapping a `Locale` into a
     * `Configuration` by hand: on Android 13+ it hands the choice to the platform's own per-app
     * language setting, so the system Settings screen agrees with the app, and below 13 the
     * compat layer persists and re-applies it. Doing it manually gets neither.
     */
    fun apply(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(
            language.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()
        )
    }
}
