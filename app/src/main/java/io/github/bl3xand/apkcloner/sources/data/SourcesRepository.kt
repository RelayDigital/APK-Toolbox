package io.github.bl3xand.apkcloner.sources.data

import io.github.bl3xand.apkcloner.compat.packageInfoOrNull
import io.github.bl3xand.apkcloner.compat.SIGNERS_FLAG
import io.github.bl3xand.apkcloner.compat.versionCodeLong
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import io.github.bl3xand.apkcloner.clone.build.ApkCloner
import io.github.bl3xand.apkcloner.install.InstallCoordinator
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.doStringsMatchUnderRegEx
import io.github.bl3xand.apkcloner.sources.core.errorText
import io.github.bl3xand.apkcloner.sources.core.isUpdateable
import io.github.bl3xand.apkcloner.sources.core.reconcileTrackedVersion
import io.github.bl3xand.apkcloner.sources.core.versionDetectionPossible
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.model.appFromStoredJson
import io.github.bl3xand.apkcloner.sources.model.preserveUserFields
import io.github.bl3xand.apkcloner.sources.source.AppSource
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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

    private val _checkErrors = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Why the last check of an app failed, by app; an app that was checked without trouble since
     * is not in it. Kept for as long as the app runs: the next check says anew.
     */
    val checkErrors: StateFlow<Map<String, String>> = _checkErrors

    /** Notes that the check of [id] failed with [error], or - with null - that it went through. */
    fun setCheckError(id: String, error: String?) {
        _checkErrors.update { if (error == null) it - id else it + (id to error) }
    }

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val appsDir = File(context.filesDir, "sources/app_data").apply { mkdirs() }

    /** Downloads live in the cache: the system may reclaim them, a new download brings them back. */
    val apkDir: File get() = File(context.externalCacheDir ?: context.cacheDir, "sources").apply { mkdirs() }

    private val saveCounter = AtomicInteger()

    /**
     * Counts writes and removals of the app files. Two uses: the temp names a save writes
     * never collide, and loadApps can tell whether anything was stored while it was reading
     * the files.
     */
    private val storageMutations = AtomicInteger()

    /** Checking the sources for new versions. */
    val updates = UpdateChecker(context, this)

    /** Export and import of the list. */
    val backup = SourcesBackup(context, this)

    fun entry(id: String): AppEntry? = synchronized(lock) { entries[id] }

    fun all(): List<AppEntry> = synchronized(lock) { entries.values.toList() }

    /**
     * The tracked apps that are installed as clones of themselves, by the clone's package. Such a
     * clone is kept up to date from the app's source, not from an installed original.
     */
    fun installedAsClones(): Map<String, AppEntry> {
        // Asked from outside the tab too, where nothing may have been read yet.
        if (!loadedOnce) runCatching { loadApps() }
        return all().mapNotNull { entry -> entry.app.clonePackage?.let { it to entry } }.toMap()
    }

    private fun publish() {
        _apps.value = all()
    }

    fun setDownload(id: String, state: DownloadState?) {
        _downloads.value = _downloads.value.toMutableMap().also { if (state == null) it.remove(id) else it[id] = state }
    }

    /**
     * Whether anything is being downloaded or installed: by this tab, or by a background
     * worker through the shared install coordinator. What the "already busy" guards check.
     */
    fun areDownloadsRunning(): Boolean = _downloads.value.isNotEmpty() || InstallCoordinator.isInstalling

    fun installedInfo(packageName: String?): PackageInfo? {
        if (packageName == null) return null
        return packageManager.packageInfoOrNull(packageName, SIGNERS_FLAG)
    }

    /**
     * Brings "installed as a clone" in line with the device. A clone that is set up and is there
     * is what the app is installed as; one that is not there (never installed, or removed since)
     * is not, and the app is again what is installed under its own package, if anything.
     */
    private fun settleClone(app: TrackedApp): TrackedApp {
        val target = app.cloneTarget ?: return app
        val info = installedInfo(target)
        val isClone = info != null && (
            app.clonePackage != null ||
                info.applicationInfo?.sourceDir?.let(ApkCloner::readMetadata)?.originalPackage == app.id
            )
        return when {
            isClone == (app.clonePackage != null) -> app
            isClone -> app.withSetting(SettingKeys.CLONE_ACTIVE, true)
            else -> app.withSetting(SettingKeys.CLONE_ACTIVE, false).withSetting(SettingKeys.CLONE_SOURCE_SIGNER, "")
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
    fun hasSignerConflict(entry: AppEntry): Boolean {
        if (entry.installedInfo == null) return false
        val offered = storedApkCertHashes(entry.app.id)
        if (entry.app.clonePackage == null) return signersClash(entry.installedInfo, offered)
        // A clone is signed with this app's key, so the system has nothing to compare. What is
        // compared instead is the signer of the APK the clone was built from.
        val builtFrom = entry.app.cloneSourceSigner
        return settings.verifySigningCertHashes && offered.isNotEmpty() && builtFrom.isNotEmpty() && !builtFrom.containsAll(offered)
    }

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
            val isLatest = runCatching { sourceOf(app).isLatestBuildInstalled(app, info?.versionName, info?.versionCodeLong ?: 0) }.getOrNull()
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

    @Volatile
    private var loadedOnce = false

    fun loadApps() {
        _loading.value = true
        loadedOnce = true
        try {
            val removedIds = ArrayList<String>()
            val corrected = ArrayList<TrackedApp>()
            val invalid = ArrayList<Triple<String, String, String>>()
            val loaded = LinkedHashMap<String, AppEntry>()
            // Read before the files: whether anything is stored while they are being read is
            // what the swap below decides on.
            val mutationsBefore = storageMutations.get()
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
                    settleClone(app).takeIf { it != app }?.let {
                        app = it
                        corrected.add(it)
                    }
                    val info = installedInfo(app.devicePackage)
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
            // The swap runs under the same lock as saveApps and removeApps, and on whether anything was
            // stored while the files were being read. Nothing was: the loaded snapshot replaces
            // the memory. Something was: the memory holds the fresher records for that change,
            // so the snapshot is laid over it without the clear() - the saved and removed apps
            // keep what their writers put there, and an app the snapshot no longer has a file
            // for goes. What used to happen - clear() and putAll() wiping a save that landed
            // between the snapshot and the swap - cannot anymore.
            synchronized(lock) {
                if (mutationsBefore == storageMutations.get()) {
                    entries.clear()
                    entries.putAll(loaded)
                } else {
                    // The ids on disk now - the files could have been removed while they were
                    // being read, and a removed app must go from the memory too, not only from
                    // the snapshot's put: an id kept only by the stale snapshot is a ghost row
                    // pointing at no file.
                    val onDisk = appsDir.listFiles { file -> file.name.lowercase().endsWith(".json") }
                        ?.mapTo(HashSet()) { it.name.removeSuffix(".json") } ?: emptySet()
                    for ((id, entry) in loaded) entries[id] = entry
                    entries.keys.retainAll { it in onDisk }
                }
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
     * Builds the record to store for [appIn], given the state of the list at the moment it is
     * saved: the app and what the system knows about its installed copy. Null when the app is
     * not to be stored at all.
     */
    private fun prepareForSave(
        appIn: TrackedApp,
        existing: AppEntry?,
        attemptToCorrectInstallStatus: Boolean,
        onlyIfExists: Boolean,
        reuseInstalledInfo: Boolean,
    ): Pair<TrackedApp, PackageInfo?>? {
        var app = settleClone(appIn)
        // Not when the app has just been told to live under another package.
        val canReuse = reuseInstalledInfo && existing != null && existing.app.devicePackage == app.devicePackage
        val info = if (canReuse) existing!!.installedInfo else installedInfo(app.devicePackage)
        if (!canReuse && info != null) {
            // The name the system shows wins over the one the source reports.
            info.applicationInfo?.loadLabel(packageManager)?.toString()?.let { app = app.copy(name = it) }
        }
        if (attemptToCorrectInstallStatus) app = reconcileInstallStatus(app, info) ?: app
        return if (onlyIfExists && existing == null) null else app to info
    }

    /**
     * The part of saving that runs under the entries lock, so that the record a save builds from
     * the state of the list is stored against that same state. The lock spans the whole
     * read-modify-write: read the entry, build the record, write the file, put it in the map.
     * Nothing in it runs for long - the file write is the rename of a temp file written just
     * before it - and nothing it calls takes the lock again (publish and the auto-export are
     * outside).
     */
    private fun saveAppsLocked(
        apps: List<TrackedApp>,
        attemptToCorrectInstallStatus: Boolean,
        onlyIfExists: Boolean,
        reuseInstalledInfo: Boolean,
        preserveUserFields: Boolean,
    ) = synchronized(lock) {
        for (input in apps) {
            val existing = entries[input.id]
            // A fetch saved over a record the user has touched since the fetch started must
            // not overwrite those touches: only what a fetch owns is taken from it.
            val app = if (preserveUserFields && existing != null) {
                val source = runCatching { sourceOf(input) }.getOrNull()
                preserveUserFields(
                    existing.app, input,
                    branchKeyOf = source?.let { src -> { file: NamedUrl -> src.releaseBranchKeyOf(file) } },
                )
            } else {
                input
            }
            val (prepared, info) = prepareForSave(app, existing, attemptToCorrectInstallStatus, onlyIfExists, reuseInstalledInfo)
                ?: continue
            writeAppJson(prepared)
            storageMutations.incrementAndGet()
            val sourceType = existing?.sourceType ?: runCatching { sourceOf(prepared).sourceIdentifier }.getOrNull()
            entries[app.id] = AppEntry(prepared, info, sourceType)
        }
    }

    /**
     * Stores apps and refreshes their in-memory state. With [onlyIfExists] an app that is not
     * tracked (any more) is not written. With [preserveUserFields] the saved record gives way,
     * field by field, to what the list already holds for the user's own words - settings, the
     * choice of a release file, the installed version once one is set - so that a fetch built
     * before a user edit does not overwrite it. Meant for records a check fetched; a record the
     * user edited is the user's whole word and is stored as it is (the default).
     */
    fun saveApps(
        apps: List<TrackedApp>,
        attemptToCorrectInstallStatus: Boolean = true,
        onlyIfExists: Boolean = true,
        reuseInstalledInfo: Boolean = false,
        preserveUserFields: Boolean = false,
    ) {
        saveAppsLocked(apps, attemptToCorrectInstallStatus, onlyIfExists, reuseInstalledInfo, preserveUserFields)
        publish()
        backup.scheduleAutoExport()
    }

    fun removeApps(ids: List<String>) {
        // Whatever drops an app from the list leaves a line: an app that is gone without one
        // was never stored.
        if (ids.isNotEmpty()) AppLog.info("No longer tracked: ${ids.joinToString()}")
        val downloads = apkDir.listFiles() ?: emptyArray()
        // The lock spans the file deletion and the entry removal, so no save of an app being
        // removed can land its file and entry back after them (a save running concurrently on
        // another thread sees the entry gone and, with onlyIfExists, stores nothing). The
        // counter moves too: a load that read the files before the removal must see it as a
        // mutation and not resurrect the app from its stale snapshot.
        synchronized(lock) {
            for (id in ids) {
                File(appsDir, "$id.json").delete()
                forgetApkCertHashes(id)
                setCheckError(id, null)
                downloads.filter { it.name.startsWith("$id-") }.forEach { it.deleteRecursively() }
                entries.remove(id)
                storageMutations.incrementAndGet()
            }
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
        !(app.updatesOff && app.installedVersion != null) && isUpdateable(app.installedVersion, app.latestVersion, settings.hideDowngrades && !app.isVersionPseudo)

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
