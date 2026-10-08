package io.github.bl3xand.apkcloner.settings

import android.content.Context

class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Master switch: look for outdated clones in the background at all. */
    var checkUpdates: Boolean
        get() = prefs.getBoolean(KEY_CHECK_UPDATES, false)
        set(value) = prefs.edit().putBoolean(KEY_CHECK_UPDATES, value).apply()

    /** Install what the check finds; when off, the check only notifies. */
    var autoInstall: Boolean
        get() = prefs.getBoolean(KEY_AUTO_INSTALL, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_INSTALL, value).apply()

    /** Index into [CHECK_INTERVAL_DAYS]. */
    var checkInterval: Int
        get() = prefs.getInt(KEY_CHECK_INTERVAL, DEFAULT_CHECK_INTERVAL).coerceIn(CHECK_INTERVAL_DAYS.indices)
        set(value) = prefs.edit().putInt(KEY_CHECK_INTERVAL, value).apply()

    var installMethod: InstallMethod
        get() = InstallMethod.entries.firstOrNull { it.name == prefs.getString(KEY_INSTALL_METHOD, null) }
            ?: InstallMethod.STOCK
        set(value) = prefs.edit().putString(KEY_INSTALL_METHOD, value.name).apply()

    val checkIntervalDays: Long get() = CHECK_INTERVAL_DAYS[checkInterval].toLong()

    enum class InstallMethod { STOCK, SHIZUKU }

    companion object {
        private const val KEY_INSTALL_METHOD = "install_method"
        private const val KEY_CHECK_UPDATES = "check_updates"
        private const val KEY_AUTO_INSTALL = "auto_install"
        private const val KEY_CHECK_INTERVAL = "check_interval"
        private const val DEFAULT_CHECK_INTERVAL = 2

        /** Slider stops, from once a day to once a year. Matches the check_intervals string array. */
        val CHECK_INTERVAL_DAYS = intArrayOf(1, 3, 7, 14, 30, 90, 180, 365)
    }
}
