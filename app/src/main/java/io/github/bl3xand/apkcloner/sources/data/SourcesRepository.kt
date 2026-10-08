package io.github.bl3xand.apkcloner.sources.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import io.github.bl3xand.apkcloner.BuildConfig
import io.github.bl3xand.apkcloner.settings.AppSettings
import io.github.bl3xand.apkcloner.sources.core.CertHashes
import io.github.bl3xand.apkcloner.sources.core.MultiAppMultiError
import io.github.bl3xand.apkcloner.sources.core.RateLimitError
import io.github.bl3xand.apkcloner.sources.core.RepositoryRenamedError
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.doStringsMatchUnderRegEx
import io.github.bl3xand.apkcloner.sources.core.effectiveMinUpdateAgeDays
import io.github.bl3xand.apkcloner.sources.core.errorText
import io.github.bl3xand.apkcloner.sources.core.isReleaseTooYoung
import io.github.bl3xand.apkcloner.sources.core.isUpdateable
import io.github.bl3xand.apkcloner.sources.core.reconcileTrackedVersion
import io.github.bl3xand.apkcloner.sources.core.versionDetectionPossible
import io.github.bl3xand.apkcloner.sources.model.CheckUpdatesException
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.model.appFromStoredJson
import io.github.bl3xand.apkcloner.sources.model.applyMinAgeSuppression
import io.github.bl3xand.apkcloner.sources.source.AppSource
import io.github.bl3xand.apkcloner.sources.source.DEFAULT_FETCH_CONCURRENCY
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A tracked app together with what the system knows about its installed copy. */
class AppEntry(val app: TrackedApp, val installedInfo: PackageInfo?, val sourceType: String?) {
    val name: String get() = app.finalName
    val author: String get() = app.finalAuthor

    /** The stored APK list is stale, or the app asks for a fresh one before every download. */
    val needsRefreshBeforeDownload: Boolean
        get() = app.settings.getBool("refreshBeforeDownload") || app.apkUrls.firstOrNull()?.url == "placeholder"

    val hasMultipleSigners: Boolean get() = installedInfo?.signingInfo?.hasMultipleSigners() ?: false

    val certificateHashes: List<String> get() = certHashesOf(installedInfo).toList()
}

/** [progress] is 0..100, or -1 while installing. */
data class DownloadState(val progress: Double, val receivedBytes: Long? = null, val totalBytes: Long? = null)

fun certHashesOf(info: PackageInfo?): Set<String> {
    val signing = info?.signingInfo ?: return emptySet()
    val signatures = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
    return signatures?.map { CertHashes.format(it.toByteArray()) }?.toSet() ?: emptySet()
}

/** The version the system reports: its code or its name, as the app is set up. */
fun realInstalledVersionOf(app: TrackedApp, info: PackageInfo?): String? {
    if (info == null) return null
    return if (app.settings.getBool(SettingKeys.VERSION_CODE_AS_OS_VERSION)) info.longVersionCode.toString() else info.versionName
}

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

    /** 0..1 while an update check runs, else null. */
    private val _refreshProgress = MutableStateFlow<Double?>(null)
    val refreshProgress: StateFlow<Double?> = _refreshProgress

    private val appsDir = File(context.filesDir, "sources/app_data").apply { mkdirs() }

    /** Downloads live in the cache: the system may reclaim them, a new download brings them back. */
    val apkDir: File get() = File(context.externalCacheDir ?: context.cacheDir, "sources").apply { mkdirs() }

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var pendingAutoExport: ScheduledFuture<*>? = null
    private val saveCounter = AtomicInteger()
    private val checkLock = Any()

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
            app = app.copy(installedVersion = real)
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
            SourcesLog.info("Could not reconcile version formats for: ${app.id}")
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
                } catch (e: org.json.JSONException) {
                    // Broken beyond reading: set it aside so that it stops failing.
                    SourcesLog.error("Corrupt JSON, renaming ${file.name}", e)
                    file.renameTo(File(file.path + ".corrupt"))
                    continue
                } catch (e: Exception) {
                    SourcesLog.warn("Error loading app ${file.name} (skipped, file kept): $e")
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
                invalid.forEach { SourcesLog.error("Removing app ${it.first} (${it.second}) due to load error: ${it.third}") }
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
        scheduleAutoExport()
    }

    fun removeApps(ids: List<String>) {
        val downloads = apkDir.listFiles() ?: emptyArray()
        for (id in ids) {
            File(appsDir, "$id.json").delete()
            downloads.filter { it.name.startsWith("$id-") }.forEach { it.deleteRecursively() }
            synchronized(lock) { entries.remove(id) }
        }
        if (ids.isNotEmpty()) {
            publish()
            scheduleAutoExport()
        }
    }

    // ---- update checks ---------------------------------------------------------------------

    /** The newest state of an app from its source, not yet saved. Null while a rename is pending. */
    fun fetchUpdate(id: String): TrackedApp? {
        val current = entry(id)?.app ?: return null
        if (current.hasPendingRepoRename) return null
        var fresh = SourceRegistry.getApp(sourceOf(current), current.url, current.additionalSettings, currentApp = current)
        if (fresh.latestVersion != current.latestVersion &&
            isReleaseTooYoung(fresh.releaseDate, effectiveMinUpdateAgeDays(current.additionalSettings, settings))
        ) {
            // Too young to be offered yet (a guard against bad or hijacked releases).
            fresh = applyMinAgeSuppression(current, fresh)
        }
        fresh = if (current.preferredApkIndex < fresh.apkUrls.size) {
            fresh.copy(preferredApkIndex = current.preferredApkIndex)
        } else if (fresh.apkUrls.isNotEmpty()) {
            fresh.copy(preferredApkIndex = 0)
        } else fresh
        return fresh
    }

    private fun fetchUpdateWithHandshakeRetry(id: String): TrackedApp? {
        var attempt = 0
        while (true) {
            try {
                return fetchUpdate(id)
            } catch (e: SSLHandshakeException) {
                // Parallel handshakes with one host fail on some networks; try again shortly.
                if (attempt++ >= 2) throw e
                Thread.sleep(250L + Random.nextInt(501))
            }
        }
    }

    /** Checks one app and saves it; returns it only when its latest version changed. */
    fun checkUpdate(id: String): TrackedApp? {
        val current = entry(id)?.app ?: return null
        val fresh = fetchUpdate(id) ?: return null
        saveApps(listOf(fresh))
        return if (fresh.latestVersion != current.latestVersion) fresh else null
    }

    /** The interval of the app's background check, which the Sources tab shares. */
    val updateIntervalMinutes: Long get() = AppSettings(context).checkIntervalDays * 24 * 60

    /** Apps due for a check (all of them when [forceAll]), longest unchecked first. */
    fun getAppsSortedByUpdateCheckTime(onlyInstalledOrTrackOnly: Boolean = false, forceAll: Boolean = false): List<String> {
        val dueBefore = Instant.now().minusSeconds(updateIntervalMinutes * 60)
        return all()
            .filter { forceAll || it.app.lastUpdateCheck == null || it.app.lastUpdateCheck.isBefore(dueBefore) }
            .filter { !onlyInstalledOrTrackOnly || it.app.installedVersion != null || it.app.settings.getBool(SettingKeys.TRACK_ONLY) }
            .sortedBy { it.app.lastUpdateCheck ?: Instant.EPOCH }
            .map { it.app.id }
    }

    fun isAppUpdateable(app: TrackedApp): Boolean =
        isUpdateable(app.installedVersion, app.latestVersion, settings.hideDowngrades)

    /**
     * Checks many apps, a few at a time. Returns the apps with a new version; failures are
     * collected per app and thrown together as [CheckUpdatesException] at the end.
     */
    fun checkUpdates(
        specificIds: List<String>? = null,
        forceAll: Boolean = false,
        throwErrorsForRetry: Boolean = false,
    ): List<TrackedApp> = synchronized(checkLock) {
        val ids = specificIds?.toList()
            ?: getAppsSortedByUpdateCheckTime(settings.onlyCheckInstalledOrTrackOnlyApps, forceAll)
        val updates = java.util.Collections.synchronizedList(ArrayList<TrackedApp>())
        val fetched = java.util.Collections.synchronizedList(ArrayList<TrackedApp>())
        val failed = java.util.Collections.synchronizedList(ArrayList<TrackedApp>())
        val errors = MultiAppMultiError()
        val completed = AtomicInteger()
        val next = AtomicInteger()
        var fatal: Throwable? = null
        _refreshProgress.value = 0.0
        try {
            val workers = (0 until minOf(DEFAULT_FETCH_CONCURRENCY, ids.size)).map {
                Thread {
                    while (fatal == null) {
                        val index = next.getAndIncrement()
                        if (index >= ids.size) return@Thread
                        val id = ids[index]
                        val current = entry(id)?.app
                        try {
                            val fresh = fetchUpdateWithHandshakeRetry(id)
                            if (fresh != null) {
                                fetched.add(fresh)
                                if (current != null && fresh.latestVersion != current.latestVersion &&
                                    isAppUpdateable(fresh)
                                ) {
                                    updates.add(fresh)
                                }
                            }
                        } catch (e: Throwable) {
                            if ((e is RateLimitError || e is IOException) && throwErrorsForRetry) {
                                fatal = e
                            } else if (e is RepositoryRenamedError) {
                                current?.let { saveApps(listOf(it.copy(pendingRepoRenameUrl = e.newUrl))) }
                            } else {
                                synchronized(errors) { errors.add(id, e, appName = entry(id)?.name) }
                                SourcesLog.warn("Update check failed for $id: ${errorText(e)}")
                                // Still counts as checked, or the background task would retry it
                                // every time it runs.
                                current?.let { failed.add(it.copy(lastUpdateCheck = Instant.now())) }
                            }
                        }
                        _refreshProgress.value = completed.incrementAndGet().toDouble() / ids.size
                    }
                }.apply { start() }
            }
            workers.forEach { it.join() }
            fatal?.let { throw it }
            if (fetched.isNotEmpty()) saveApps(fetched.toList(), reuseInstalledInfo = true)
            if (failed.isNotEmpty()) {
                saveApps(failed.toList(), attemptToCorrectInstallStatus = false, reuseInstalledInfo = true)
            }
            if (errors.idsByErrorString.isNotEmpty()) throw CheckUpdatesException(updates.toList(), errors)
            updates.toList()
        } finally {
            _refreshProgress.value = null
        }
    }

    /** Apps that are not installed, or whose installed version is behind the latest one. */
    fun findAppIdsWithPendingUpdates(installedOnly: Boolean = false, nonInstalledOnly: Boolean = false): List<String> {
        val result = ArrayList<String>()
        for (entry in all()) {
            val app = entry.app
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

    // ---- import and export -----------------------------------------------------------------

    fun generateExportJson(appIds: List<String>? = null, overrideExportSettings: Int? = null): JSONObject {
        val exportSettings = overrideExportSettings ?: settings.exportSettings
        val appList = JSONArray()
        for (entry in all()) {
            if (appIds != null && entry.app.id !in appIds) continue
            if (settings.exportInstalledOnly && entry.app.installedVersion == null) continue
            // Per-app credentials are secrets too.
            val app = if (exportSettings < 2) {
                entry.app.copy(additionalSettings = entry.app.additionalSettings.filterKeys { !it.endsWith("-creds") })
            } else entry.app
            appList.put(app.toJson())
        }
        val settingsJson: Any = if (exportSettings > 0) {
            JSONObject().also { json ->
                for ((key, value) in settings.prefs.all) {
                    if (exportSettings < 2 && key.endsWith("-creds")) continue
                    json.put(key, if (value is Set<*>) JSONArray(value) else value)
                }
            }
        } else JSONObject.NULL
        return JSONObject().apply {
            put("schemaVersion", EXPORT_SCHEMA_VERSION)
            put("exportedAt", Instant.now().toString())
            put("appVersion", BuildConfig.VERSION_NAME)
            put("apps", appList)
            put("settings", settingsJson)
        }
    }

    /** The ids of the apps in an export, without importing it. */
    fun appIdsInImportJson(text: String): List<String> {
        val apps = when (val decoded = JsonValues.parse(text)) {
            is Map<*, *> -> decoded["apps"] as? List<*>
            is List<*> -> decoded
            else -> null
        } ?: return emptyList()
        return apps.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
    }

    /** Imports apps (and settings, when present). Returns the apps and whether settings came along. */
    fun importJson(text: String): Pair<List<TrackedApp>, Boolean> {
        val imported: List<TrackedApp>
        var importedSettings: Map<String, Any?>? = null
        try {
            val decoded = JsonValues.parse(text)
            val appMaps: List<*>
            if (decoded is Map<*, *>) {
                val version = (decoded["schemaVersion"] as? Number)?.toInt() ?: 1
                if (version > EXPORT_SCHEMA_VERSION) {
                    throw SourceError("Export schema v$version is newer than this app supports (v$EXPORT_SCHEMA_VERSION).")
                }
                appMaps = decoded["apps"] as? List<*> ?: emptyList<Any?>()
                @Suppress("UNCHECKED_CAST")
                importedSettings = decoded["settings"] as? Map<String, Any?>
            } else {
                appMaps = decoded as List<*>
            }
            @Suppress("UNCHECKED_CAST")
            imported = appMaps.map { appFromStoredJson(it as Map<String, Any?>) }
        } catch (e: Exception) {
            throw SourceError("${Tr.get("failedToImport")}: ${errorText(e)}")
        }
        val adjusted = imported.map { it.copy(installedVersion = realInstalledVersionOf(it, installedInfo(it.id))) }
        saveApps(adjusted, onlyIfExists = false)
        importedSettings?.let { values ->
            val editor = settings.prefs.edit()
            for ((key, value) in values) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putInt(key, value.toInt())
                    is Double -> editor.putFloat(key, value.toFloat())
                    is List<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    is String -> editor.putString(key, value)
                }
            }
            editor.apply()
        }
        return adjusted to (importedSettings != null)
    }

    /** The export folder, when one is set and still reachable. */
    fun exportDirUri(): Uri? {
        val uri = settings.exportDir?.let(Uri::parse) ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return if (granted) uri else null
    }

    fun setExportDir(uri: Uri?) {
        val resolver = context.contentResolver
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        for (permission in resolver.persistedUriPermissions) {
            if (permission.uri != uri) runCatching { resolver.releasePersistableUriPermission(permission.uri, flags) }
        }
        if (uri != null) resolver.takePersistableUriPermission(uri, flags)
        settings.exportDir = uri?.toString()
    }

    /** Writes an export file into the export folder; returns a readable path, or null. */
    fun export(isAuto: Boolean = false): String? {
        if (isAuto && !settings.autoExportOnChanges) return null
        val tree = exportDirUri() ?: return null
        val resolver = context.contentResolver
        val customName = settings.autoExportFileName
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        if (isAuto) {
            // The previous automatic export is replaced, not piled up.
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (name.endsWith("-auto.json") || (customName != null && name == "$customName.json")) {
                        runCatching {
                            DocumentsContract.deleteDocument(
                                resolver, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                            )
                        }
                    }
                }
            }
        }
        val displayName = if (isAuto && customName != null) {
            "$customName.json"
        } else {
            "$EXPORT_FILE_PREFIX-${Instant.now().toString().replace(':', '-')}${if (isAuto) "-auto" else ""}.json"
        }
        val document = DocumentsContract.createDocument(resolver, parent, "application/json", displayName)
            ?: throw SourceError(Tr.get("unexpectedError"))
        resolver.openOutputStream(document)?.use { it.write(generateExportJson().toString(4).toByteArray()) }
            ?: throw SourceError(Tr.get("unexpectedError"))
        return DocumentsContract.getTreeDocumentId(tree).substringAfter(':').let { "/$it" }
    }

    /** Many saves in a row lead to one export. */
    private fun scheduleAutoExport() {
        if (!settings.autoExportOnChanges) return
        synchronized(scheduler) {
            pendingAutoExport?.cancel(false)
            pendingAutoExport = scheduler.schedule(
                { runCatching { export(isAuto = true) }.onFailure { SourcesLog.warn("Auto-export failed: $it") } },
                2, TimeUnit.SECONDS,
            )
        }
    }

    companion object {
        const val EXPORT_SCHEMA_VERSION = 2
        const val EXPORT_FILE_PREFIX = "apk-toolbox-sources-export"

        @Volatile
        private var instance: SourcesRepository? = null

        fun get(context: Context): SourcesRepository = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                SourcesEnvironment.init(app)
                SourcesLog.init(app)
                SourcesRepository(app).also { instance = it }
            }
        }

        /** A light, saturated colour for a new category. */
        fun randomLightColor(): Int {
            val hue = (Random.nextInt(120) * 137.508f) % 360f
            return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.95f))
        }
    }
}
