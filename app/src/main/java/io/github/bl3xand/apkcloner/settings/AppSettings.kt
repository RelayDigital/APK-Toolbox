package io.github.bl3xand.apkcloner.settings

import android.content.Context
import io.github.bl3xand.apkcloner.log.AppLog
import org.json.JSONArray
import org.json.JSONObject

class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Master switch: look for outdated clones in the background at all. */
    var checkUpdates: Boolean
        get() = prefs.getBoolean(SettingsKeys.CHECK_UPDATES, false)
        set(value) = put(SettingsKeys.CHECK_UPDATES, value)

    /** Install what the check finds; when off, the check only notifies. */
    var autoInstall: Boolean
        get() = prefs.getBoolean(SettingsKeys.AUTO_INSTALL, false)
        set(value) = put(SettingsKeys.AUTO_INSTALL, value)

    /** Index into [CHECK_INTERVAL_DAYS]. */
    var checkInterval: Int
        get() = prefs.getInt(SettingsKeys.CHECK_INTERVAL, DEFAULT_CHECK_INTERVAL).coerceIn(CHECK_INTERVAL_DAYS.indices)
        set(value) = put(SettingsKeys.CHECK_INTERVAL, value)

    var installMethod: InstallMethod
        get() = InstallMethod.entries.firstOrNull { it.name == prefs.getString(SettingsKeys.INSTALL_METHOD, null) }
            ?: InstallMethod.STOCK
        set(value) = put(SettingsKeys.INSTALL_METHOD, value.name)

    /** What the background check covers: clones, apps tracked from sources, or both. */
    var checkClones: Boolean
        get() = prefs.getBoolean(SettingsKeys.CHECK_CLONES, true)
        set(value) = put(SettingsKeys.CHECK_CLONES, value)

    var checkSources: Boolean
        get() = prefs.getBoolean(SettingsKeys.CHECK_SOURCES, true)
        set(value) = put(SettingsKeys.CHECK_SOURCES, value)

    /** Background installs wait for an unmetered network and/or a charger. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(SettingsKeys.WIFI_ONLY, false)
        set(value) = put(SettingsKeys.WIFI_ONLY, value)

    var chargingOnly: Boolean
        get() = prefs.getBoolean(SettingsKeys.CHARGING_ONLY, false)
        set(value) = put(SettingsKeys.CHARGING_ONLY, value)

    /** Experimental: install with `su`, taking precedence over the chosen method. */
    var useRoot: Boolean
        get() = prefs.getBoolean(SettingsKeys.USE_ROOT, false)
        set(value) = put(SettingsKeys.USE_ROOT, value)

    /** Last choices made in the merge sheet. */
    var mergeSign: Boolean
        get() = prefs.getBoolean(SettingsKeys.MERGE_SIGN, true)
        set(value) = put(SettingsKeys.MERGE_SIGN, value)

    var cloneBadge: Boolean
        get() = prefs.getBoolean(SettingsKeys.CLONE_BADGE, true)
        set(value) = put(SettingsKeys.CLONE_BADGE, value)

    /** Clones, by package, that are kept at the version they have. */
    var frozenClones: Set<String>
        get() = prefs.getStringSet(SettingsKeys.FROZEN_CLONES, emptySet()).orEmpty().toSet()
        set(value) {
            prefs.edit().putStringSet(SettingsKeys.FROZEN_CLONES, value).apply()
            AppLog.info("Setting ${SettingsKeys.FROZEN_CLONES} = ${value.sorted().joinToString()}")
        }

    /**
     * The categories of clones, by package. The categories themselves are the ones the tracked
     * apps have; a clone that a source keeps current has its categories there.
     */
    var cloneCategories: Map<String, Set<String>>
        get() = runCatching {
            val json = JSONObject(prefs.getString(SettingsKeys.CLONE_CATEGORIES, null) ?: "{}")
            json.keys().asSequence().associateWith { key ->
                json.getJSONArray(key).let { names -> (0 until names.length()).map(names::getString).toSet() }
            }
        }.getOrDefault(emptyMap())
        set(value) {
            val json = JSONObject()
            value.filterValues { it.isNotEmpty() }.forEach { (key, names) -> json.put(key, JSONArray(names.sorted())) }
            prefs.edit().putString(SettingsKeys.CLONE_CATEGORIES, json.toString()).apply()
        }

    var installSign: Boolean
        get() = prefs.getBoolean(SettingsKeys.INSTALL_SIGN, false)
        set(value) = put(SettingsKeys.INSTALL_SIGN, value)

    var mergeForce: Boolean
        get() = prefs.getBoolean(SettingsKeys.MERGE_FORCE, false)
        set(value) = put(SettingsKeys.MERGE_FORCE, value)

    val checkIntervalDays: Long get() = CHECK_INTERVAL_DAYS[checkInterval].toLong()

    /** Stores a setting; every change is put on record, so the log shows how the app was set up. */
    private fun put(key: String, value: Any) {
        prefs.edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                else -> putString(key, value.toString())
            }
        }.apply()
        AppLog.debug("Setting $key = $value")
    }

    companion object {
        private const val DEFAULT_CHECK_INTERVAL = 2

        /** Slider stops, from once a day to once a year. Matches the check_intervals string array. */
        val CHECK_INTERVAL_DAYS = intArrayOf(1, 3, 7, 14, 30, 90, 180, 365)
    }
}
