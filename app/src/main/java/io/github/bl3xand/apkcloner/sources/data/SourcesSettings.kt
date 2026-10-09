package io.github.bl3xand.apkcloner.sources.data

import android.content.Context
import android.content.SharedPreferences
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.settings.AppSettings
import io.github.bl3xand.apkcloner.sources.core.SourceSettings
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import org.json.JSONObject

/**
 * Settings of the Sources tab. Key names follow the reference app so that its exported settings
 * can be imported as they are.
 */
class SourcesSettings private constructor(context: Context) : SourceSettings {
    val prefs: SharedPreferences = context.getSharedPreferences("sources_settings", Context.MODE_PRIVATE)

    // The schedule, the install method and their restrictions are shared with the rest of the app.
    private val app = AppSettings(context)

    override fun getString(key: String): String? = try {
        prefs.getString(key, null)?.takeIf { it.isNotEmpty() }
    } catch (e: ClassCastException) {
        null
    }

    override fun getBool(key: String): Boolean = bool(key, false)

    override fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        // Tokens and passwords are settings too; what they are set to is nobody's business.
        AppLog.debug("Sources setting $key = ${if (isSecret(key)) "(set)" else value}")
    }

    fun setBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        AppLog.debug("Sources setting $key = $value")
    }

    private fun isSecret(key: String): Boolean = SECRET_KEY_PARTS.any { key.contains(it, ignoreCase = true) }

    private fun bool(key: String, default: Boolean): Boolean = try {
        prefs.getBoolean(key, default)
    } catch (e: ClassCastException) {
        default
    }

    private fun int(key: String, default: Int): Int = try {
        prefs.getInt(key, default)
    } catch (e: ClassCastException) {
        default
    }

    val checkInBackground: Boolean get() = app.checkSources

    val useRoot: Boolean get() = app.useRoot

    var checkOnStart: Boolean
        get() = bool("checkOnStart", false)
        set(value) = setBool("checkOnStart", value)

    /** 0 added, 1 name+author, 2 author+name, 3 release date. */
    var sortColumn: Int
        get() = int("sortColumn", 1).coerceIn(0, 3)
        set(value) = prefs.edit().putInt("sortColumn", value).apply()

    /** 0 ascending, 1 descending. */
    var sortOrder: Int
        get() = int("sortOrder", 0).coerceIn(0, 1)
        set(value) = prefs.edit().putInt("sortOrder", value).apply()

    var showAppWebpage: Boolean
        get() = bool("showAppWebpage", false)
        set(value) = setBool("showAppWebpage", value)

    var pinUpdates: Boolean
        get() = bool("pinUpdates", true)
        set(value) = setBool("pinUpdates", value)

    var buryNonInstalled: Boolean
        get() = bool("buryNonInstalled", false)
        set(value) = setBool("buryNonInstalled", value)

    /** none, category or source. */
    var groupBy: String
        get() = getString("groupBy")?.takeIf { it in listOf("none", "category", "source") } ?: "none"
        set(value) = setString("groupBy", value)

    var hideTrackOnlyWarning: Boolean
        get() = bool("hideTrackOnlyWarning", false)
        set(value) = setBool("hideTrackOnlyWarning", value)

    var hideAPKOriginWarning: Boolean
        get() = bool("hideAPKOriginWarning", false)
        set(value) = setBool("hideAPKOriginWarning", value)

    var showAppDowngradeError: Boolean
        get() = bool("showAppDowngradeError", true)
        set(value) = setBool("showAppDowngradeError", value)

    override var hideDowngrades: Boolean
        get() = bool("hideDowngrades", true)
        set(value) = setBool("hideDowngrades", value)

    var includePrereleasesByDefault: Boolean
        get() = bool("includePrereleasesByDefault", false)
        set(value) = setBool("includePrereleasesByDefault", value)

    var removeOnExternalUninstall: Boolean
        get() = bool("removeOnExternalUninstall", false)
        set(value) = setBool("removeOnExternalUninstall", value)

    var checkUpdateOnDetailPage: Boolean
        get() = bool("checkUpdateOnDetailPage", false)
        set(value) = setBool("checkUpdateOnDetailPage", value)

    val enableBackgroundUpdates: Boolean get() = app.autoInstall

    override var enableCertificatePinning: Boolean
        get() = bool("enableCertificatePinning", false)
        set(value) = setBool("enableCertificatePinning", value)

    val bgUpdatesOnWiFiOnly: Boolean get() = app.wifiOnly

    val bgUpdatesWhileChargingOnly: Boolean get() = app.chargingOnly


    var exportDir: String?
        get() = getString("exportDir")
        set(value) = prefs.edit().apply { if (value == null) remove("exportDir") else putString("exportDir", value) }.apply()

    var autoExportOnChanges: Boolean
        get() = bool("autoExportOnChanges", false)
        set(value) = setBool("autoExportOnChanges", value)

    var autoExportFileName: String?
        get() = getString("autoExportFileName")
        set(value) {
            val cleaned = value?.replace(Regex("[/\\\\:*?\"<>|]"), "")?.trim()
            prefs.edit().apply {
                if (cleaned.isNullOrEmpty()) remove("autoExportFileName") else putString("autoExportFileName", cleaned)
            }.apply()
        }

    override var globalApkFilterRegEx: String?
        get() = getString("globalApkFilterRegEx")
        set(value) {
            val cleaned = value?.trim()
            prefs.edit().apply {
                if (cleaned.isNullOrEmpty()) remove("globalApkFilterRegEx") else putString("globalApkFilterRegEx", cleaned)
            }.apply()
        }

    var onlyCheckInstalledOrTrackOnlyApps: Boolean
        get() = bool("onlyCheckInstalledOrTrackOnlyApps", false)
        set(value) = setBool("onlyCheckInstalledOrTrackOnlyApps", value)

    var collapseGroupsOnStartup: Boolean
        get() = bool("collapseGroupsOnStartup", false)
        set(value) = setBool("collapseGroupsOnStartup", value)

    var skipBulkUpdateConfirmation: Boolean
        get() = bool("skipBulkUpdateConfirmation", false)
        set(value) = setBool("skipBulkUpdateConfirmation", value)

    override var minimumUpdateAgeDays: Int
        get() = int("minimumUpdateAgeDays", 0)
        set(value) = prefs.edit().putInt("minimumUpdateAgeDays", value.coerceAtLeast(0)).apply()

    /** 0 none, 1 without secrets, 2 everything. */
    var exportSettings: Int
        get() = int("exportSettings", 1)
        set(value) = prefs.edit().putInt("exportSettings", if (value in 0..2) value else 1).apply()

    var exportInstalledOnly: Boolean
        get() = bool("exportInstalledOnly", false)
        set(value) = setBool("exportInstalledOnly", value)

    var parallelDownloads: Boolean
        get() = bool("parallelDownloads", true)
        set(value) = setBool("parallelDownloads", value)

    /** standard, compact or dense. */

    var searchDeselected: List<String>
        get() = try {
            prefs.getStringSet("searchDeselected", null)?.toList()
        } catch (e: ClassCastException) {
            null
        } ?: SourceRegistry.sources.filter { it.sourceIdentifier !in DEFAULT_SEARCH_SOURCES }.map { it.name }
        set(value) = prefs.edit().putStringSet("searchDeselected", value.toSet()).apply()

    /** all, updatesOnly or none. */
    var actionBannerMode: String
        get() = getString("actionBannerMode")?.takeIf { it in listOf("all", "updatesOnly", "none") } ?: "updatesOnly"
        set(value) = setString("actionBannerMode", value)

    var beforeNewInstallsShareToAppVerifier: Boolean
        get() = bool("beforeNewInstallsShareToAppVerifier", true)
        set(value) = setBool("beforeNewInstallsShareToAppVerifier", value)

    var verifySigningCertHashes: Boolean
        get() = bool("verifySigningCertHashes", true)
        set(value) = setBool("verifySigningCertHashes", value)

    var shizukuPretendToBeGooglePlay: Boolean
        get() = bool("shizukuPretendToBeGooglePlay", false)
        set(value) = setBool("shizukuPretendToBeGooglePlay", value)

    var welcomeShown: Boolean
        get() = bool("welcomeShown", false)
        set(value) = setBool("welcomeShown", value)

    /** Category name to ARGB colour. */
    var categories: Map<String, Int>
        get() = try {
            val json = JSONObject(prefs.getString("categories", "{}") ?: "{}")
            json.keys().asSequence().associateWith { json.getInt(it) }
        } catch (e: Exception) {
            emptyMap()
        }
        set(value) = prefs.edit().putString("categories", JSONObject(value).toString()).apply()

    companion object {
        /** Settings whose names hold one of these are never written to the log. */
        private val SECRET_KEY_PARTS = listOf("creds", "token", "password", "key", "pat")

        /** Where a search looks until the user says otherwise. */
        private val DEFAULT_SEARCH_SOURCES = setOf("GitHub", "FDroid")

        @Volatile
        private var instance: SourcesSettings? = null

        fun get(context: Context): SourcesSettings = instance ?: synchronized(this) {
            instance ?: SourcesSettings(context.applicationContext).also { instance = it }
        }
    }
}
