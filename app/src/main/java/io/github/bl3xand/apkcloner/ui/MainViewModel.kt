package io.github.bl3xand.apkcloner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.clone.ApkCloner
import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.data.AppRepository
import io.github.bl3xand.apkcloner.clone.CloneRequest
import io.github.bl3xand.apkcloner.data.CloneInfo
import io.github.bl3xand.apkcloner.data.InstalledApps
import io.github.bl3xand.apkcloner.install.Installer
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.install.InstallReceiver
import java.io.File
import kotlinx.coroutines.Dispatchers
import io.github.bl3xand.apkcloner.settings.AppSettings
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val cloner = ApkCloner(application)
    private val repository = AppRepository(application, cloner)

    // Everything this app writes on its own lives here and is wiped as soon as it is not needed.
    private val tempDir = File(application.cacheDir, "work")
    private val updateOutput = File(tempDir, "update")

    private var allApps: List<ApkSource> = emptyList()
    private var allClones: List<CloneInfo> = emptyList()
    private var query = ""
    private var showSystem = false

    private var loaded = false

    /** What the list of clones is narrowed to. */
    var clonesFilter = ClonesFilter()
        set(value) {
            field = value
            publishApps()
        }

    /** Which tab is showing; kept here so it survives rotation. */
    var tab = MainTabs.SOURCES

    /** The side of the Cloning tab shown last: installed apps or clones. */
    var cloningSide = MainTabs.APPS

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MainEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MainEvent> = _events.asSharedFlow()

    private val output = ApkOutput(application, viewModelScope, _events)

    /** True while the result of a sheet is being installed. */
    val installing: StateFlow<Boolean> = output.installing

    /** Making a new clone, for the clone sheet. */
    val cloning = CloneController(viewModelScope, cloner, repository, tempDir, output, _events, ::setLoading) { allApps }

    /** Installing, exporting and merging splits, for the split sheet. */
    val splits = SplitController(application, viewModelScope, cloner, tempDir, output, _events, ::setLoading)


    init {
        // Leftovers from a run that was killed mid-clone.
        tempDir.deleteRecursively()
        tempDir.mkdirs()
        viewModelScope.launch { InstallReceiver.sessionFinished.collect { refresh() } }
        viewModelScope.launch { SourcesRepository.get(application).apps.drop(1).collect { onTrackedAppsChanged() } }
    }

    /**
     * A clone can be one that a source keeps current, and what the Sources tab does to it shows
     * here too. Who tracks which clone is cheap to tell again; what is installed is only read
     * again when a tracked clone was installed, updated or removed.
     */
    private fun onTrackedAppsChanged() {
        if (!loaded) return
        val tracked = SourcesRepository.get(getApplication()).installedAsClones()
        val known = allClones.mapTo(HashSet()) { it.app.packageName }
        val reinstalled = allClones.any { tracked[it.app.packageName]?.app?.installedVersion != it.tracked?.app?.installedVersion } ||
            tracked.any { (packageName, entry) -> packageName !in known && entry.installedInfo != null }
        if (reinstalled) {
            refresh()
        } else if (allClones.any { tracked[it.app.packageName]?.app != it.tracked?.app }) {
            allClones = allClones.map { it.withTracked(tracked[it.app.packageName]) }
            publishApps()
        }
    }

    fun setPermissions(fileAccess: Boolean, canInstall: Boolean) {
        val before = _uiState.value
        if (before.hasFileAccess != fileAccess || before.canInstall != canInstall) {
            AppLog.info("Permissions: file access ${if (fileAccess) "granted" else "missing"}, installing apps ${if (canInstall) "allowed" else "not allowed"}")
        }
        _uiState.update { it.copy(hasFileAccess = fileAccess, canInstall = canInstall) }
        if (fileAccess && canInstall) refresh()
    }

    private fun setLoading(loading: Boolean) = _uiState.update { it.copy(loading = loading) }

    fun setQuery(text: String) {
        query = text.trim()
        publishApps()
    }

    fun setShowSystem(show: Boolean) {
        showSystem = show
        publishApps()
    }

    fun updateClone(clone: CloneInfo) = updateClones(listOf(clone))

    /**
     * Re-builds a clone without [removed] - or, with nothing in it, with every permission of the
     * original back in. Like an update it installs over the same package, so the data is kept, and
     * shows progress for the whole wait.
     */
    fun setClonePermissions(clone: CloneInfo, removed: Set<String>) {
        if (removed == clone.removedPermissions) return
        rebuildClones(listOf(clone), "Changing the permissions (${removed.size} removed) of") { it.permissionsRequest(removed) }
    }

    /** Files a clone under [categories]; a clone that a source keeps current is filed there instead. */
    fun setCloneCategories(clone: CloneInfo, categories: Set<String>) {
        val settings = AppSettings(getApplication())
        settings.cloneCategories = settings.cloneCategories + (clone.app.packageName to categories)
        allClones = allClones.map { if (it.app.packageName == clone.app.packageName) it.withCategories(categories) else it }
        publishApps()
    }

    /** Keeps a clone at the version it has, or lets it follow its original again. */
    fun setCloneFrozen(clone: CloneInfo, frozen: Boolean) {
        val settings = AppSettings(getApplication())
        settings.frozenClones = if (frozen) settings.frozenClones + clone.app.packageName else settings.frozenClones - clone.app.packageName
        allClones = allClones.map { if (it.app.packageName == clone.app.packageName) it.withFrozen(frozen) else it }
        publishApps()
    }

    /**
     * Uninstalls a clone through the system prompt, showing progress on its card for the whole
     * wait (like an install) and reloading the list afterwards so the card closes once it is gone.
     */
    fun uninstallClone(clone: CloneInfo) {
        if (_uiState.value.updatingClone != null || _uiState.value.uninstallingClone != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(uninstallingClone = clone.app.packageName) }
            try {
                AppLog.info("Uninstalling clone ${clone.app.packageName}")
                Installer.uninstall(getApplication(), clone.app.packageName)
            } finally {
                readInstalled()?.let {
                    allApps = it.apps
                    allClones = it.clones
                }
                _uiState.update { it.copy(uninstallingClone = null) }
                publishApps()
            }
        }
    }

    /**
     * The Refresh of the Clones side: looks at every clone and its original again and updates
     * the clones that are behind. Says so when there is nothing to do.
     */
    fun refreshClones() {
        if (_uiState.value.updatingClone != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val installed = readInstalled()
            _uiState.update { it.copy(loading = false) }
            if (installed == null) return@launch
            allApps = installed.apps
            allClones = installed.clones
            publishApps()
            // A clone that a source keeps current is updated from there, with the rest of the tracked apps.
            val outdated = allClones.filter { it.wantsUpdate && it.tracked == null }
            AppLog.info("Clones checked: ${allClones.size}, behind: ${outdated.size}")
            if (outdated.isEmpty()) _events.tryEmit(MainEvent.Message(R.string.clones_up_to_date)) else updateClones(outdated)
        }
    }

    private fun updateClones(clones: List<CloneInfo>) = rebuildClones(clones, "Updating", CloneInfo::updateRequest)

    /**
     * Builds each clone again from its original as [request] describes and installs the result over
     * it, one after another. [action] names what is being done, for the log.
     */
    private fun rebuildClones(clones: List<CloneInfo>, action: String, request: (CloneInfo) -> CloneRequest?) {
        if (clones.isEmpty() || _uiState.value.updatingClone != null) return
        viewModelScope.launch(Dispatchers.IO) {
            for (clone in clones) {
                val build = request(clone) ?: continue
                _uiState.update { it.copy(updatingClone = clone.app.packageName) }
                try {
                    AppLog.info("$action clone ${clone.app.packageName}")
                    output.install(cloner.clone(build, updateOutput) { _, _, _ -> })
                } catch (e: Exception) {
                    AppLog.error("$action clone ${clone.app.packageName} failed", e)
                    _events.tryEmit(MainEvent.Message(R.string.status_failed, e.message ?: e.javaClass.simpleName))
                } finally {
                    // The installer session holds its own copy by now.
                    updateOutput.deleteRecursively()
                }
            }
            _uiState.update { it.copy(updatingClone = null) }
        }
    }

    fun refresh() {
        if (!_uiState.value.permissionsGranted) return
        viewModelScope.launch {
            // Later refreshes replace the lists silently instead of flashing the progress bar.
            if (!loaded) _uiState.update { it.copy(loading = true) }
            val installed = readInstalled()
            _uiState.update { it.copy(loading = false) }
            if (installed == null) return@launch
            loaded = true
            allApps = installed.apps
            allClones = installed.clones
            publishApps()
        }
    }

    /**
     * What is installed, read off the main thread. The system can fail to answer - too many
     * packages for one reply, or a package that goes away while it is read - and then the lists
     * stay as they were rather than the app going down.
     */
    private suspend fun readInstalled(): InstalledApps? = withContext(Dispatchers.IO) {
        runCatching { repository.installed() }
            .onFailure { AppLog.error("Reading the installed apps failed", it) }
            .getOrNull()
    }

    private fun publishApps() {
        fun matches(app: ApkSource) =
            query.isEmpty() || app.label.contains(query, true) || app.packageName.contains(query, true)
        _uiState.update { state ->
            state.copy(
                apps = allApps.filter { (showSystem || !it.isSystem) && matches(it) },
                splitApps = allApps.filter { it.apkPaths.size > 1 && (showSystem || !it.isSystem) && matches(it) },
                clones = allClones.filter { clone ->
                    matches(clone.app) && (clonesFilter.notUpdated || !clone.frozen) &&
                        (clonesFilter.categories.isEmpty() || clonesFilter.categories.intersect(clone.categories).isNotEmpty()) && when {
                        clone.tracked != null -> clonesFilter.fromSource
                        clone.original != null -> clonesFilter.fromOriginal
                        // One that has neither is always worth seeing.
                        else -> true
                    }
                },
                outdatedClones = allClones.count { it.updateAvailable },
            )
        }
    }
}
