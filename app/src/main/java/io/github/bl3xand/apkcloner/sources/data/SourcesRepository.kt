package io.github.bl3xand.apkcloner.sources.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.doStringsMatchUnderRegEx
import io.github.bl3xand.apkcloner.sources.core.errorText
import io.github.bl3xand.apkcloner.sources.core.isUpdateable
import io.github.bl3xand.apkcloner.sources.core.reconcileTrackedVersion
import io.github.bl3xand.apkcloner.sources.core.versionDetectionPossible
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.model.appFromStoredJson
import io.github.bl3xand.apkcloner.sources.source.AppSource
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONException

/** Tracked apps: their files, their state, update checks, import and export. */
class SourcesRepository private constructor(private val context: Context) {
    val settings = SourcesSettings.get(context)
    private val packageManager = context.packageManager
    private val lock = Any()
    private val entries = LinkedHashMap<String, AppEntry>()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val appsDir = File(context.filesDir, "sources/app_data").apply { mkdirs() }

    /** Downloads live in the cache: the system may reclaim them, a new download brings them back. */
    val apkDir: File get() = File(context.externalCacheDir ?: context.cacheDir, "sources").apply { mkdirs() }

    private val saveCounter = AtomicInteger()

    /** Checking the sources for new versions. */
    val updates = UpdateChecker(context, this)

    /** Export and import of the list. */
    val backup = SourcesBackup(context, this)

    fun entry(id: String): AppEntry? = synchronized(lock) { entries[id] }

    fun all(): List<AppEntry> = synchronized(lock) { entries.values.toList() }

    private fun publish() {
        _apps.value = all()
    }

    fun setDownload(id: String, state: DownloadState?) {
        _downloads.value = _downloads.value.toMutableMap().also { if (state == null) it.remove(id) else it[id] = state }
    }

    fun areDownloadsRunning(): Boolean = _downloads.value.isNotEmpty()

    fun installedInfo(packageName: String?): PackageInfo? {
        if (packageName == null) return null
        return try {
            packageManager.getPackageInfo(
                packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun sourceOf(app: TrackedApp): AppSource = SourceRegistry.getSource(app.url, app.overrideSource)

    // ---- signer of what a source offers ------------------------------------------------------

    /** The signer of the last APK seen from the source [appId] is tracked from; empty if none was. */
    fun storedApkCertHashes(appId: String): Set<String> =
        settings.getString(APK_CERT_PREFIX + appId).orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun storeApkCertHashes(appId: String, apkHashes: Set<String>) {
        if (apkHashes.isNotEmpty()) settings.setString(APK_CERT_PREFIX + appId, apkHashes.joinToString(","))
    }

    /** The record belongs to one source of a package: it goes when that source stops being tracked. */
    fun forgetApkCertHashes(appId: String) {
        settings.prefs.edit().remove(APK_CERT_PREFIX + appId).apply()
    }

    /** Whether an APK signed with [apkHashes] cannot go over what is installed as [info]. */
    fun signersClash(info: PackageInfo?, apkHashes: Set<String>): Boolean {
        if (!settings.verifySigningCertHashes || apkHashes.isEmpty()) return false
        val installedHashes = certHashesOf(info)
        return installedHashes.isNotEmpty() && !installedHashes.containsAll(apkHashes)
    }

    /**
     * Whether the release on offer cannot be installed over what is on the device because the two
     * are signed differently. Worked out live from the installed app's certificate and the signer
     * last recorded for this app's source, so it clears itself once the clashing app is gone or a
     * matching one is installed. What is installed then is not this app, whatever its version says.
     */
    fun hasSignerConflict(entry: AppEntry): Boolean =
        entry.installedInfo != null && signersClash(entry.installedInfo, storedApkCertHashes(entry.app.id))

    private fun isNaiveDetection(app: TrackedApp, source: AppSource = sourceOf(app)): Boolean =
        app.settings.getBool("naiveStandardVersionDetection") || source.naiveStandardVersionDetection

    fun isVersionDetectionPossible(app: TrackedApp, info: PackageInfo?): Boolean {
        val source = sourceOf(app)
        return versionDetectionPossible(
            trackOnly = app.settings.getBool(SettingKeys.TRACK_ONLY),
            releaseDateAsVersion = app.settings.getBool(SettingKeys.RELEASE_DATE_AS_VERSION),
            isHtmlWithNoVersionDetection = source.sourceIdentifier == "HTML" &&
                app.settings.getStringOrNull("versionExtractionRegEx").isNullOrEmpty(),
            versionDetectionDisallowed = source.versionDetectionDisallowed,
            realInstalledVersion = realInstalledVersionOf(app, info),
            trackedVersion = app.installedVersion,
            latestVersion = app.latestVersion,
            naiveStandardVersionDetection = isNaiveDetection(app, source),
        )
    }

    /** Brings the recorded install state in line with the device. Null when nothing changes. */
    fun reconcileInstallStatus(appIn: TrackedApp, info: PackageInfo?): TrackedApp? {
        var app = appIn
        var modified = false
        val trackOnly = app.settings.getBool(SettingKeys.TRACK_ONLY)
        val standard = app.settings.getBool(SettingKeys.VERSION_DETECTION)
        val real = realInstalledVersionOf(app, info)
        if (info == null && app.installedVersion != null && !trackOnly) {
            app = app.copy(installedVersion = null)
            modified = true
        } else if (real != null && app.installedVersion == null) {
            // Found on the device without having been installed from here. A source whose
            // versions are not the app's own can still say whether it is the latest release.
            val isLatest = runCatching { sourceOf(app).isLatestBuildInstalled(app, info?.versionName, info?.longVersionCode ?: 0) }.getOrNull()
            app = app.copy(installedVersion = if (isLatest == true) app.latestVersion else real)
            modified = true
        }
        val corrected = reconcileTrackedVersion(
            trackedVersion = app.installedVersion,
            realInstalledVersion = real,
            latestVersion = app.latestVersion,
            versionDetectionIsStandard = standard,
            naiveStandardVersionDetection = isNaiveDetection(app),
        )
        if (corrected != null && corrected != app.installedVersion) {
            app = app.copy(installedVersion = corrected)
            modified = true
        }
        // Version formats that cannot be compared: stop pretending they can.
        if (info != null && standard && !isVersionDetectionPossible(app, info)) {
            app = app.withSetting(SettingKeys.VERSION_DETECTION, false)
            AppLog.info("Could not reconcile version formats for: ${app.id}")
            modified = true
        }
        return if (modified) app else null
    }

    fun loadApps() {
        _loading.value = true
        try {
            val removedIds = ArrayList<String>()
            val corrected = ArrayList<TrackedApp>()
            val invalid = ArrayList<Triple<String, String, String>>()
            val loaded = LinkedHashMap<String, AppEntry>()
            val files = appsDir.listFiles { file -> file.name.lowercase().endsWith(".json") }
                ?.sortedBy { it.lastModified() } ?: emptyList()
            for (file in files) {
                var app = try {
                    appFromStoredJson(JsonValues.parseObject(file.readText()))
                } catch (e: JSONException) {
                    // Broken beyond reading: set it aside so that it stops failing.
                    AppLog.error("Corrupt JSON, renaming ${file.name}", e)
                    file.renameTo(File(file.path + ".corrupt"))
                    continue
                } catch (e: Exception) {
                    AppLog.warn("Error loading app ${file.name} (skipped, file kept): $e")
                    continue
                }
                try {
                    val sourceType = sourceOf(app).sourceIdentifier
                    val info = installedInfo(app.id)
                    reconcileInstallStatus(app, info)?.let {
                        app = it
                        corrected.add(it)
                        if (it.installedVersion == null) removedIds.add(it.id)
                    }
                    loaded[app.id] = AppEntry(app, info, sourceType)
                } catch (e: Exception) {
                    invalid.add(Triple(app.id, app.finalName, errorText(e)))
                }
            }
            synchronized(lock) {
                entries.clear()
                entries.putAll(loaded)
            }
            if (invalid.isNotEmpty()) {
                invalid.forEach { AppLog.error("Removing app ${it.first} (${it.second}) due to load error: ${it.third}") }
                removeApps(invalid.map { it.first })
                onAppsRemoved?.invoke(invalid.map { it.second to it.third })
            }
            if (removedIds.isNotEmpty() && settings.removeOnExternalUninstall) removeApps(removedIds)
            val stillThere = corrected.filter { entry(it.id) != null }
            if (stillThere.isNotEmpty()) saveApps(stillThere, attemptToCorrectInstallStatus = false)
        } finally {
            _loading.value = false
            publish()
        }
    }

    /** Called with (name, reason) pairs when stored apps had to be dropped. */
    var onAppsRemoved: ((List<Pair<String, String>>) -> Unit)? = null

    private fun writeAppJson(app: TrackedApp) {
        appsDir.mkdirs()
        val target = File(appsDir, "${app.id}.json")
        // A unique temporary file: two saves of one app must not interleave.
        val temp = File(appsDir, "${app.id}.json.${System.nanoTime()}-${saveCounter.incrementAndGet()}.tmp")
        temp.writeText(app.toJson().toString())
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) throw IOException("Could not save ${target.name}")
        }
    }

    /**
     * Stores apps and refreshes their in-memory state. With [onlyIfExists] an app that is not
     * tracked (any more) is not written.
     */
    fun saveApps(
        apps: List<TrackedApp>,
        attemptToCorrectInstallStatus: Boolean = true,
        onlyIfExists: Boolean = true,
        reuseInstalledInfo: Boolean = false,
    ) {
        for (input in apps) {
            var app = input
            val existing = entry(app.id)
            val canReuse = reuseInstalledInfo && existing != null
            val info = if (canReuse) existing!!.installedInfo else installedInfo(app.id)
            if (!canReuse && info != null) {
                // The name the system shows wins over the one the source reports.
                info.applicationInfo?.loadLabel(packageManager)?.toString()?.let { app = app.copy(name = it) }
            }
            if (attemptToCorrectInstallStatus) app = reconcileInstallStatus(app, info) ?: app
            if (!onlyIfExists || existing != null) {
                writeAppJson(app)
                val sourceType = existing?.sourceType ?: runCatching { sourceOf(app).sourceIdentifier }.getOrNull()
                synchronized(lock) { entries[app.id] = AppEntry(app, info, sourceType) }
            }
        }
        publish()
        backup.scheduleAutoExport()
    }

    fun removeApps(ids: List<String>) {
        val downloads = apkDir.listFiles() ?: emptyArray()
        for (id in ids) {
            File(appsDir, "$id.json").delete()
            forgetApkCertHashes(id)
            downloads.filter { it.name.startsWith("$id-") }.forEach { it.deleteRecursively() }
            synchronized(lock) { entries.remove(id) }
        }
        if (ids.isNotEmpty()) {
            publish()
            backup.scheduleAutoExport()
        }
    }

    /**
     * Whether the latest release is one to offer. A release that looks older than what is
     * installed is normally not - unless the two versions are not of one kind, as with an app
     * whose version is read from a file name or was set by hand: there "older" means nothing.
     */
    fun isAppUpdateable(app: TrackedApp): Boolean =
        isUpdateable(app.installedVersion, app.latestVersion, settings.hideDowngrades && !app.isVersionPseudo)

    /**
     * Apps that are not installed, or whose installed version is behind the latest one. An app
     * whose package is taken by a differently-signed build has nothing pending: it cannot be
     * installed, and the version on the device is not a version of it.
     */
    fun findAppIdsWithPendingUpdates(installedOnly: Boolean = false, nonInstalledOnly: Boolean = false): List<String> {
        val result = ArrayList<String>()
        for (entry in all()) {
            val app = entry.app
            if (hasSignerConflict(entry)) continue
            val installed = app.installedVersion
            if (installedOnly) {
                if (installed == null) continue
            } else if (nonInstalledOnly) {
                if (installed == null) result.add(app.id)
                continue
            }
            if (installed == null || (installedVersionDiffers(app, installed) && isAppUpdateable(app))) result.add(app.id)
        }
        return result
    }

    private fun installedVersionDiffers(app: TrackedApp, installed: String): Boolean {
        val regex = (app.additionalSettings["versionExtractionRegEx"] as? String) ?: ""
        return if (regex.isEmpty()) installed != app.latestVersion
        else !doStringsMatchUnderRegEx(regex, installed, app.latestVersion)
    }

    /** Adds apps by URL with default settings; returns [url or id, error] pairs for failures. */
    fun addAppsByUrl(urls: List<String>, sourceOverride: AppSource? = null): List<Pair<String, String>> {
        val (apps, errors) = SourceRegistry.getAppsByUrlNaive(urls, all().map { it.app.url }.toSet(), sourceOverride)
        val failures = errors.map { it.key to errorText(it.value) }.toMutableList()
        for (app in apps) {
            val existing = entry(app.id)
            if (existing != null) {
                failures.add(app.id to "${Tr.get("appAlreadyAdded")}: ${existing.app.name}")
            } else {
                saveApps(listOf(app), onlyIfExists = false)
            }
        }
        return failures
    }

    // ---- categories --------------------------------------------------------------------------

    /** Stores the categories and removes deleted ones from the apps that had them. */
    fun setCategories(categories: Map<String, Int>) {
        val changed = all().map { it.app }.filter { app -> app.categories.any { it !in categories } }
            .map { app -> app.copy(categories = app.categories.filter { it in categories }) }
        settings.categories = categories
        if (changed.isNotEmpty()) saveApps(changed) else publish()
    }

    /** Gives a colour to categories that only exist on (imported) apps. */
    fun addMissingCategories() {
        val categories = settings.categories.toMutableMap()
        var added = false
        for (entry in all()) {
            for (category in entry.app.categories) {
                if (category !in categories) {
                    categories[category] = randomLightColor()
                    added = true
                }
            }
        }
        if (added) settings.categories = categories
    }

    companion object {
        private const val APK_CERT_PREFIX = "apkCertHashes:"

        @Volatile
        private var instance: SourcesRepository? = null

        fun get(context: Context): SourcesRepository = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                SourcesEnvironment.init(app)
                AppLog.init(app)
                SourcesRepository(app).also { instance = it }
            }
        }

        /** A light, saturated colour for a new category. */
        fun randomLightColor(): Int {
            val hue = (Random.nextInt(120) * 137.508f) % 360f
            return Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.95f))
        }
    }
}
