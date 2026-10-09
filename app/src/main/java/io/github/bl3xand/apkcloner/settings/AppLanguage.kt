package io.github.bl3xand.apkcloner.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The language the app is shown in when it is not the system's. The choice is kept by the
 * system, as the app's own language, so it is the same one its settings page for the app shows.
 */
enum class AppLanguage(val tag: String, val ownName: String) {
    /** No choice: whatever the system is set to. */
    SYSTEM("", ""),
    ENGLISH("en", "English"),
    RUSSIAN("ru", "Русский"),
    CHINESE("zh", "中文");

    /** Makes this the app's language; the screens on show are built again in it. */
    fun apply() {
        AppCompatDelegate.setApplicationLocales(
            if (this == SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }

    companion object {
        /** What is chosen now. */
        fun current(): AppLanguage {
            val chosen = AppCompatDelegate.getApplicationLocales()
            if (chosen.isEmpty) return SYSTEM
            return entries.firstOrNull { it != SYSTEM && it.tag == chosen[0]?.language } ?: SYSTEM
        }
    }
}
