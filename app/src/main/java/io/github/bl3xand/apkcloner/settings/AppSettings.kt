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

    /** What the background check covers: clones, apps tracked from sources, or both. */
    var checkClones: Boolean
        get() = prefs.getBoolean(KEY_CHECK_CLONES, true)
        set(value) = prefs.edit().putBoolean(KEY_CHECK_CLONES, value).apply()

    var checkSources: Boolean
        get() = prefs.getBoolean(KEY_CHECK_SOURCES, true)
        set(value) = prefs.edit().putBoolean(KEY_CHECK_SOURCES, value).apply()

    /** Background installs wait for an unmetered network and/or a charger. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var chargingOnly: Boolean
        get() = prefs.getBoolean(KEY_CHARGING_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_CHARGING_ONLY, value).apply()

    /** Experimental: install with `su`, taking precedence over the chosen method. */
    var useRoot: Boolean
        get() = prefs.getBoolean(KEY_USE_ROOT, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_ROOT, value).apply()

    /** Last choices made in the merge sheet. */
    var mergeSign: Boolean
        get() = prefs.getBoolean(KEY_MERGE_SIGN, true)
        set(value) = prefs.edit().putBoolean(KEY_MERGE_SIGN, value).apply()

    var cloneBadge: Boolean
        get() = prefs.getBoolean(KEY_CLONE_BADGE, true)
        set(value) = prefs.edit().putBoolean(KEY_CLONE_BADGE, value).apply()

    var installSign: Boolean
        get() = prefs.getBoolean(KEY_INSTALL_SIGN, false)
        set(value) = prefs.edit().putBoolean(KEY_INSTALL_SIGN, value).apply()

    var mergeForce: Boolean
        get() = prefs.getBoolean(KEY_MERGE_FORCE, false)
        set(value) = prefs.edit().putBoolean(KEY_MERGE_FORCE, value).apply()

    val checkIntervalDays: Long get() = CHECK_INTERVAL_DAYS[checkInterval].toLong()

    enum class InstallMethod { STOCK, SHIZUKU }

    companion object {
        private const val KEY_INSTALL_METHOD = "install_method"
        private const val KEY_CHECK_CLONES = "check_clones"
        private const val KEY_CHECK_SOURCES = "check_sources"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_CHARGING_ONLY = "charging_only"
        private const val KEY_USE_ROOT = "use_root"
        private const val KEY_MERGE_SIGN = "merge_sign"
        private const val KEY_INSTALL_SIGN = "install_sign"
        private const val KEY_CLONE_BADGE = "clone_badge"
        private const val KEY_MERGE_FORCE = "merge_force"
        private const val KEY_CHECK_UPDATES = "check_updates"
        private const val KEY_AUTO_INSTALL = "auto_install"
        private const val KEY_CHECK_INTERVAL = "check_interval"
        private const val DEFAULT_CHECK_INTERVAL = 2

        /** Slider stops, from once a day to once a year. Matches the check_intervals string array. */
        val CHECK_INTERVAL_DAYS = intArrayOf(1, 3, 7, 14, 30, 90, 180, 365)
    }
}
