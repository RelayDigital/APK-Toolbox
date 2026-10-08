package io.github.bl3xand.apkcloner.settings

import android.content.Context

class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoUpdate: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    /** Index into [CHECK_INTERVAL_DAYS]. */
    var checkInterval: Int
        get() = prefs.getInt(KEY_CHECK_INTERVAL, DEFAULT_CHECK_INTERVAL).coerceIn(CHECK_INTERVAL_DAYS.indices)
        set(value) = prefs.edit().putInt(KEY_CHECK_INTERVAL, value).apply()

    val checkIntervalDays: Long get() = CHECK_INTERVAL_DAYS[checkInterval].toLong()

    companion object {
        private const val KEY_AUTO_UPDATE = "auto_update"
        private const val KEY_CHECK_INTERVAL = "check_interval"
        private const val DEFAULT_CHECK_INTERVAL = 2

        /** Slider stops, from once a day to once a year. Matches the check_intervals string array. */
        val CHECK_INTERVAL_DAYS = intArrayOf(1, 3, 7, 14, 30, 90, 180, 365)
    }
}
