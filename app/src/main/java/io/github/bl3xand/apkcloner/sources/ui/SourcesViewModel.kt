package io.github.bl3xand.apkcloner.sources.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.sources.core.RepositoryRenamedError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.data.AppEntry
import io.github.bl3xand.apkcloner.sources.data.DownloadState
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import io.github.bl3xand.apkcloner.sources.install.InstallPrompts
import io.github.bl3xand.apkcloner.sources.install.SourcesInstaller
import io.github.bl3xand.apkcloner.sources.model.CheckUpdatesException
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import io.github.bl3xand.apkcloner.sources.telegram.TelegramClient
import io.github.bl3xand.apkcloner.sources.work.SourcesNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourcesViewModel(application: Application) : AndroidViewModel(application) {
    val repo = SourcesRepository.get(application)
    val installer = SourcesInstaller.get(application)
    private val settings = repo.settings

    private val filter = MutableStateFlow(AppsFilter())
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val collapsed = MutableStateFlow<Set<String?>>(emptySet())
    private val settingsVersion = MutableStateFlow(0)
    private var collapseInitialised = false
    private var checkedOnStart = false

    private val _events = MutableSharedFlow<SourcesEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    /** Apps whose uninstall prompt is up, so their card can show progress for the whole wait. */
    private val _uninstalling = MutableStateFlow<Set<String>>(emptySet())
    val uninstalling: StateFlow<Set<String>> = _uninstalling.asStateFlow()

    private val _candidate = MutableStateFlow<AppCandidate?>(null)
    val candidate: StateFlow<AppCandidate?> = _candidate.asStateFlow()

    /** Shows [app] on its own page without tracking it yet. */
    fun propose(app: TrackedApp, apkHashes: Set<String>) {
        val sourceType = runCatching { repo.sourceOf(app).sourceIdentifier }.getOrNull()
        _candidate.value = AppCandidate(AppEntry(app, repo.installedInfo(app.id), sourceType), apkHashes)
        _events.tryEmit(SourcesEvent.OpenApp(app.id))
    }

    /** The page of the candidate was closed without making room for it. */
    fun discardCandidate(id: String) {
        _candidate.update { if (it?.entry?.app?.id == id) null else it }
    }

    /** What the page of [id] is about: the app being added, or else the tracked one. */
    fun shownEntry(id: String): AppEntry? = _candidate.value?.entry?.takeIf { it.app.id == id } ?: repo.entry(id)

    /** Apps being downloaded or installed at the moment. */
    private val obtaining = HashSet<String>()

    /** Set by the screen while it is shown; installs started here ask their questions through it. */
    var prompts: InstallPrompts? = null

    val uiState: StateFlow<SourcesUiState> = combine(
        combine(repo.apps, repo.downloads, repo.loading, repo.updates.progress) { apps, downloads, loading, progress ->
            Quad(apps, downloads, loading, progress)
        },
        filter, selected, collapsed, settingsVersion,
    ) { data, filter, selected, collapsed, _ ->
        buildState(data.first, data.second, data.third, data.fourth, filter, selected, collapsed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SourcesUiState())

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    init {
        repo.onAppsRemoved = { SourcesNotifications.appsRemoved(application, it) }
        // Signing in or out changes what the rows of its apps say.
        viewModelScope.launch { TelegramClient.auth.collect { settingsVersion.value++ } }
        // So does a check that failed, or went through after one that had.
        viewModelScope.launch { repo.checkErrors.collect { settingsVersion.value++ } }
        reload()
    }

    /** Re-reads the stored apps and the state of their installed copies. */
    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.loadApps()
                if (!checkedOnStart) {
                    checkedOnStart = true
                    if (settings.checkOnStart && repo.all().isNotEmpty()) refresh()
                }
            } catch (e: Exception) {
                _events.tryEmit(SourcesEvent.Error(e))
            }
        }
    }

    /** Settings that change how the list looks were edited. */
    fun settingsChanged() {
        settingsVersion.value++
    }

    private fun matches(entry: AppEntry, filter: AppsFilter): Boolean {
        val app = entry.app
        val updatable = repo.isAppUpdateable(app)
        if (app.installedVersion == app.latestVersion && !filter.includeUpToDate) return false
        // A hidden downgrade counts as up to date.
        if (app.installedVersion != null && app.installedVersion != app.latestVersion && !updatable &&
            !filter.includeUpToDate
        ) {
            return false
        }
        if (app.installedVersion == null && !filter.includeNonInstalled) return false
        for (token in filter.name.split(' ').filter { it.isNotBlank() }) {
            if (!entry.name.lowercase().contains(token.lowercase())) return false
        }
        for (token in filter.author.split(' ').filter { it.isNotBlank() }) {
            if (!entry.author.lowercase().contains(token.lowercase())) return false
        }
        if (filter.id.isNotEmpty() && !app.id.contains(filter.id)) return false
        if (filter.categories.isNotEmpty() && filter.categories.intersect(app.categories.toSet()).isEmpty()) return false
        if (filter.source.isNotEmpty() && entry.sourceType != filter.source) return false
        return true
    }

    private fun sorted(entries: List<AppEntry>): List<AppEntry> {
        val descending = settings.sortOrder == 1
        return when (settings.sortColumn) {
            0 -> if (descending) entries.reversed() else entries
            3 -> {
                // Apps without a date always come last.
                val (dated, undated) = entries.partition { it.app.releaseDate != null }
                val ordered = dated.sortedBy { it.app.releaseDate }
                (if (descending) ordered.reversed() else ordered) + undated
            }
            else -> {
                val key: (AppEntry) -> String = if (settings.sortColumn == 2) {
                    { (it.author + it.name).lowercase() }
                } else {
                    { (it.name + it.author).lowercase() }
                }
                if (descending) entries.sortedByDescending(key) else entries.sortedBy(key)
            }
        }
    }

    private fun buildState(
        all: List<AppEntry>,
        downloads: Map<String, DownloadState>,
        loading: Boolean,
        progress: Double?,
        filter: AppsFilter,
        selectedIn: Set<String>,
        collapsedIn: Set<String?>,
    ): SourcesUiState {
        val existingIds = all.map { it.app.id }.toSet()
        val selected = selectedIn.intersect(existingIds)
        val pendingAll = repo.findAppIdsWithPendingUpdates(installedOnly = true).toSet()
        val conflicts = all.filter(repo::hasSignerConflict).map { it.app.id }.toSet()

        var listed = sorted(all.filter { matches(it, filter) })
        if (settings.pinUpdates) listed = listed.filter { it.app.id in pendingAll } + listed.filter { it.app.id !in pendingAll }
        if (settings.buryNonInstalled) {
            listed = listed.filter { it.app.installedVersion != null } + listed.filter { it.app.installedVersion == null }
        }
        listed = listed.filter { it.app.hasPendingRepoRename } +
            listed.filter { !it.app.hasPendingRepoRename && it.app.pinned } +
            listed.filter { !it.app.hasPendingRepoRename && !it.app.pinned }

        // What "update all" would act on: the selection if there is one, else what is listed.
        val scope = if (selected.isEmpty()) listed.map { it.app.id }.toSet() else selected
        val trackOnly = ArrayList<String>()
        val updates = ArrayList<String>()
        val installs = ArrayList<String>()
        for (entry in all) {
            val app = entry.app
            // Nothing can be done with it until the build in its place is removed.
            if (app.id !in scope || app.id in conflicts) continue
            val isTrackOnly = app.settings.getBool(SettingKeys.TRACK_ONLY)
            when {
                app.installedVersion == null -> if (isTrackOnly) trackOnly.add(app.id) else installs.add(app.id)
                app.id in pendingAll -> if (isTrackOnly) trackOnly.add(app.id) else updates.add(app.id)
            }
        }

        val rows = ArrayList<ListRow>()
        val bannerMode = settings.actionBannerMode
        if (bannerMode != "none" && updates.size + installs.size + trackOnly.size >= 2 &&
            !(bannerMode == "updatesOnly" && updates.isEmpty())
        ) {
            rows.add(ListRow.Banner(selected.isNotEmpty()))
        }
        fun appRow(entry: AppEntry, group: String?) = ListRow.App(
            entry, downloads[entry.app.id], entry.app.id in selected,
            repo.isAppUpdateable(entry.app) && entry.app.id !in conflicts, group, entry.app.id in conflicts,
            runCatching { repo.sourceOf(entry.app).signInNote }.getOrNull(),
            repo.checkErrors.value[entry.app.id],
        )
        val groupBy = settings.groupBy
        if (groupBy == "none") {
            listed.forEach { rows.add(appRow(it, null)) }
        } else {
            val groups = LinkedHashMap<String?, MutableList<AppEntry>>()
            val sourceNames = SourceRegistry.sources.associate { it.sourceIdentifier to it.name }
            for (entry in listed) {
                if (groupBy == "category") {
                    if (entry.app.categories.isEmpty()) groups.getOrPut(null) { ArrayList() }.add(entry)
                    else entry.app.categories.forEach { groups.getOrPut(it) { ArrayList() }.add(entry) }
                } else {
                    groups.getOrPut(sourceNames[entry.sourceType] ?: entry.sourceType ?: Tr.get("noSource")) { ArrayList() }.add(entry)
                }
            }
            val keys = groups.keys.sortedWith(compareBy<String?> { it == null }.thenBy { it?.lowercase() })
            var collapsed = collapsedIn
            if (!collapseInitialised && all.isNotEmpty()) {
                collapseInitialised = true
                if (settings.collapseGroupsOnStartup) {
                    collapsed = keys.toSet()
                    this.collapsed.value = collapsed
                }
            }
            val colors = settings.categories
            for (key in keys) {
                val members = groups.getValue(key)
                val isCollapsed = key in collapsed
                val title = key ?: Tr.get(if (groupBy == "category") "noCategory" else "noSource")
                rows.add(ListRow.Group(key, title, members.size, isCollapsed, if (groupBy == "category") colors[key] else null))
                if (!isCollapsed) members.forEach { rows.add(appRow(it, key)) }
            }
        }
        return SourcesUiState(rows, all.size, loading, progress, selected, filter, updates, installs, trackOnly)
    }

    fun setQuery(text: String) {
        filter.value = filter.value.copy(name = text)
    }

    fun setFilter(value: AppsFilter) {
        filter.value = value
    }

    fun toggleSelected(id: String) {
        selected.value = selected.value.toMutableSet().also { if (!it.add(id)) it.remove(id) }
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    fun toggleGroup(key: String?) {
        collapsed.value = collapsed.value.toMutableSet().also { if (!it.add(key)) it.remove(key) }
    }

    private fun launchReporting(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                AppLog.error("Sources", e)
                _events.tryEmit(SourcesEvent.Error(if (e is CheckUpdatesException) e.errors else e))
            }
        }
    }

    /** Checks every app, or just one. */
    fun refresh(id: String? = null) = launchReporting {
        withContext(Dispatchers.IO) {
            if (id != null) repo.updates.checkUpdate(id) else repo.updates.checkUpdates(forceAll = true)
        }
    }

    /**
     * Saves an app whose options were edited and checks it again, since the options decide what
     * counts as its latest version. With [detectionEnabled] the installed version is taken
     * from the device from now on.
     */
    fun saveOptions(app: TrackedApp, detectionEnabled: Boolean) = launchReporting {
        withContext(Dispatchers.IO) {
            repo.saveApps(listOf(app))
            try {
                repo.updates.checkUpdate(app.id)
            } catch (e: RepositoryRenamedError) {
                repo.entry(app.id)?.let { repo.saveApps(listOf(it.app.copy(pendingRepoRenameUrl = e.newUrl))) }
            }
            if (detectionEnabled) {
                repo.entry(app.id)?.app?.let { current ->
                    var updated = current.withSetting(SettingKeys.VERSION_DETECTION, true)
                    if (updated.installedVersion != null) updated = updated.copy(installedVersion = updated.latestVersion)
                    repo.saveApps(listOf(updated))
                }
            }
        }
    }

    /** Downloads and installs (or, for track-only apps, marks as updated) the given apps. */
    fun obtain(requested: List<String>) {
        // A second tap while the first is still at work must not start the same download again.
        val ids = synchronized(obtaining) { requested.filter(obtaining::add) }
        if (ids.isEmpty()) return
        launchReporting {
            val installed = try {
                installer.downloadAndInstallLatestApps(ids, prompts)
            } finally {
                synchronized(obtaining) { obtaining.removeAll(ids.toSet()) }
            }
            if (installed.isNotEmpty()) {
                _events.tryEmit(SourcesEvent.Message(Tr.get(if (installed.size == 1) "msgInstalledOne" else "appsUpdated")))
                SourcesNotifications.cancel(getApplication(), SourcesNotifications.ID_UPDATES)
            }
        }
    }

    /**
     * For a signer conflict: removes the differently-signed build already on the device, showing
     * progress for the wait. Once it is gone the conflict clears and the card becomes an ordinary
     * install, so the user installs the added build with the normal button. An app that was only
     * proposed starts being tracked here, in place of whatever was tracked under its package.
     */
    fun uninstallConflicting(id: String) = launchReporting {
        if (!uninstallFromDevice(id)) return@launchReporting
        val pending = _candidate.value?.takeIf { it.entry.app.id == id }
        withContext(Dispatchers.IO) {
            if (pending != null) {
                // The signer on record was the old source's; from here on it is this one's.
                repo.forgetApkCertHashes(id)
                repo.storeApkCertHashes(id, pending.apkHashes)
                repo.saveApps(
                    listOf(pending.entry.app.copy(installedVersion = null)),
                    attemptToCorrectInstallStatus = false, onlyIfExists = false,
                )
                AppLog.info("Added $id from ${pending.entry.app.url} in place of the removed build")
            } else {
                repo.entry(id)?.app?.let {
                    repo.saveApps(listOf(it.copy(installedVersion = null)), attemptToCorrectInstallStatus = false)
                }
            }
        }
        // Only now, so that the page goes straight from the candidate to the tracked app.
        if (pending != null) discardCandidate(id)
    }

    /**
     * Removes what is installed as [id] through the system prompt, keeping the app marked as
     * being uninstalled for the whole wait. True when nothing is installed under it afterwards.
     */
    private suspend fun uninstallFromDevice(id: String): Boolean {
        if (repo.installedInfo(id) == null) return true
        _uninstalling.update { it + id }
        return try {
            installer.uninstallApp(id)
        } finally {
            _uninstalling.update { it - id }
        }
    }

    fun downloadAssets(ids: List<String>) = launchReporting {
        val currentPrompts = prompts ?: return@launchReporting
        installer.downloadAppAssets(ids, currentPrompts)
    }

    fun save(apps: List<TrackedApp>) {
        viewModelScope.launch(Dispatchers.IO) {
            // Only the changed fields are meant; anything a running check refreshed is kept.
            runCatching { repo.saveApps(apps) }.onFailure { _events.tryEmit(SourcesEvent.Error(it)) }
        }
    }

    /** Applies [change] to the current state of each app, off the main thread. */
    fun update(ids: Collection<String>, change: (TrackedApp) -> TrackedApp) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.saveApps(ids.mapNotNull { repo.entry(it)?.app }.map(change)) }
                .onFailure { _events.tryEmit(SourcesEvent.Error(it)) }
        }
    }

    fun remove(ids: List<String>, uninstall: Boolean, removeEntry: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AppLog.info("Remove ${ids.joinToString()}: uninstall=$uninstall, from the list=$removeEntry")
                // When both are asked for, the list entry is only dropped once the device uninstall
                // actually finishes. If the user cancels the system prompt, neither happens.
                val toForget = ids.filter { id ->
                    val entry = repo.entry(id)
                    val app = entry?.app
                    when {
                        app == null -> false
                        !uninstall -> true
                        app.installedVersion == null -> true
                        // What is installed under this package is another build, not this app:
                        // removing the app from the list must not take that build with it.
                        repo.hasSignerConflict(entry) -> true
                        else -> {
                            val removed = uninstallFromDevice(id)
                            if (removed) {
                                repo.saveApps(
                                    listOf(app.copy(installedVersion = null)),
                                    attemptToCorrectInstallStatus = false,
                                )
                            }
                            removed
                        }
                    }
                }
                if (removeEntry && toForget.isNotEmpty()) repo.removeApps(toForget)
                selected.value = selected.value - toForget.toSet()
            } catch (e: Exception) {
                _events.tryEmit(SourcesEvent.Error(e))
            }
        }
    }

    /** Marks apps as updated; only meaningful where the version cannot be read from the device. */
    fun markUpdated(ids: Collection<String>) = update(ids) { app ->
        val entry = repo.entry(app.id)
        if (app.installedVersion != null && !repo.isVersionDetectionPossible(app, entry?.installedInfo)) {
            app.copy(installedVersion = app.latestVersion)
        } else app
    }

    fun emit(event: SourcesEvent) {
        _events.tryEmit(event)
    }
}
