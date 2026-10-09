package io.github.bl3xand.apkcloner.ui

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.clone.ApkCloner
import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.data.AppRepository
import io.github.bl3xand.apkcloner.data.CloneInfo
import io.github.bl3xand.apkcloner.install.InstallOutcome
import io.github.bl3xand.apkcloner.install.Installer
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.merge.MergeResult
import io.github.bl3xand.apkcloner.merge.SplitStep
import io.github.bl3xand.apkcloner.merge.NotSplitException
import io.github.bl3xand.apkcloner.merge.SplitExporter
import io.github.bl3xand.apkcloner.merge.SplitLoader
import io.github.bl3xand.apkcloner.merge.SplitMerger
import io.github.bl3xand.apkcloner.merge.SplitMismatchException
import io.github.bl3xand.apkcloner.merge.SplitSource
import io.github.bl3xand.apkcloner.merge.TablesTooLargeException
import io.github.bl3xand.apkcloner.install.InstallReceiver
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class MainUiState(
    val hasFileAccess: Boolean = false,
    val canInstall: Boolean = false,
    val loading: Boolean = false,
    val apps: List<ApkSource> = emptyList(),
    val clones: List<CloneInfo> = emptyList(),
    /** Installed apps that consist of more than one APK. */
    val splitApps: List<ApkSource> = emptyList(),
    /** Package of the clone currently being rebuilt for an update. */
    val updatingClone: String? = null,
    /** How many installed clones are behind their original, whatever the search shows. */
    val outdatedClones: Int = 0,
) {
    val permissionsGranted: Boolean get() = hasFileAccess && canInstall
}

sealed interface CloneState {
    data object Idle : CloneState
    data class Running(val file: String, val index: Int, val total: Int) : CloneState
    data class Done(val apks: List<File>) : CloneState
    data class Failed(val message: String) : CloneState
}

/** What the split sheet was opened to do with the APK set it shows. */
enum class SplitMode { INSTALL, EXPORT, MERGE }

sealed interface SplitState {
    data object Idle : SplitState
    data class Running(val step: SplitStep) : SplitState
    data class Done(val result: MergeResult) : SplitState
    data class Mismatch(val splits: List<String>) : SplitState
    data class TooLarge(val megabytes: Long) : SplitState
    data class Failed(val message: String) : SplitState
}

sealed interface MainEvent {
    data class SourceReady(val source: ApkSource) : MainEvent
    data object SplitSourceReady : MainEvent
    data object FilesReceived : MainEvent
    data class Message(@StringRes val text: Int, val argument: String = "") : MainEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        /** How long to keep showing progress for an install the system never reports back on. */
        const val INSTALL_TIMEOUT_MS = 120_000L
        const val BYTES_IN_MB = 1024 * 1024
    }

    private val cloner = ApkCloner(application)
    private val repository = AppRepository(application, cloner)

    // Everything this app writes on its own lives here and is wiped as soon as it is not needed.
    private val tempDir = File(application.cacheDir, "work")
    private val pickedApk = File(tempDir, "picked.apk")
    private val sheetOutput = File(tempDir, "clone")
    private val updateOutput = File(tempDir, "update")
    private val mergeInput = File(tempDir, "merge-in")
    private val mergeWork = File(tempDir, "merge")

    private val splitLoader = SplitLoader(application)
    private val merger = SplitMerger(cloner)

    private var allApps: List<ApkSource> = emptyList()
    private var allClones: List<CloneInfo> = emptyList()
    private var query = ""
    private var showSystem = false
    private var loaded = false

    /** Which tab is showing; kept here so it survives rotation. */
    var tab = MainActivity.TAB_SOURCES

    /** The side of the Cloning tab shown last: installed apps or clones. */
    var cloningSide = MainActivity.TAB_APPS

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _cloneState = MutableStateFlow<CloneState>(CloneState.Idle)
    val cloneState: StateFlow<CloneState> = _cloneState.asStateFlow()

    private val _installing = MutableStateFlow(false)

    /** True while the result of the clone sheet is being installed. */
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    private val _events = MutableSharedFlow<MainEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MainEvent> = _events.asSharedFlow()

    private val _splitState = MutableStateFlow<SplitState>(SplitState.Idle)
    val splitState: StateFlow<SplitState> = _splitState.asStateFlow()

    /** The APK set the split sheet is currently showing, and what it is there for. */
    var splitSource: SplitSource? = null
        private set
    var splitMode = SplitMode.MERGE
        private set

    /** Files that arrived from another app, waiting for the user to say what to do with them. */
    var pendingFiles: List<Uri> = emptyList()
        private set

    /** The source the clone sheet is currently configured for. */
    var selected: ApkSource? = null
        private set

    init {
        // Leftovers from a run that was killed mid-clone.
        tempDir.deleteRecursively()
        tempDir.mkdirs()
        viewModelScope.launch { InstallReceiver.sessionFinished.collect { refresh() } }
    }

    fun setPermissions(fileAccess: Boolean, canInstall: Boolean) {
        _uiState.update { it.copy(hasFileAccess = fileAccess, canInstall = canInstall) }
        if (fileAccess && canInstall) refresh()
    }

    fun setQuery(text: String) {
        query = text.trim()
        publishApps()
    }

    fun setShowSystem(show: Boolean) {
        showSystem = show
        publishApps()
    }

    /**
     * Package and app name to offer for a new clone of [source]: `.clone` and "Clone", or the
     * first numbered variant that is not installed yet, so an app can be cloned more than once.
     */
    fun suggestClone(source: ApkSource): Pair<String, String> {
        val taken = allApps.mapTo(HashSet()) { it.packageName }
        var number = 1
        while (true) {
            val suffix = if (number == 1) "" else number.toString()
            val candidate = "${source.packageName}.clone$suffix"
            if (candidate !in taken) {
                return candidate to listOf(source.label, "Clone", suffix).filter { it.isNotEmpty() }.joinToString(" ")
            }
            number++
        }
    }

    fun select(source: ApkSource) {
        if (_cloneState.value is CloneState.Running) return
        selected = source
        _cloneState.value = CloneState.Idle
        _events.tryEmit(MainEvent.SourceReady(source))
    }

    fun loadApkFile(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val source = withContext(Dispatchers.IO) {
                tempDir.mkdirs()
                runCatching { repository.fromUri(uri, pickedApk) }.getOrNull()
            }
            _uiState.update { it.copy(loading = false) }
            if (source != null) {
                select(source)
            } else {
                pickedApk.delete()
                _events.tryEmit(MainEvent.Message(R.string.error_invalid_apk))
            }
        }
    }

    fun startClone(newPackage: String, newLabel: String, badgeIcon: Boolean) {
        val source = selected ?: return
        if (_cloneState.value is CloneState.Running) return
        _cloneState.value = CloneState.Running(source.label, 0, source.apkPaths.size)
        viewModelScope.launch {
            _cloneState.value = withContext(Dispatchers.IO) {
                try {
                    AppLog.info("Cloning ${source.packageName} ${source.versionName.orEmpty()} as $newPackage (${source.apkPaths.size} files)")
                    CloneState.Done(cloner.clone(source.cloneRequest(newPackage, newLabel, badgeIcon), sheetOutput) { file, index, total ->
                        _cloneState.value = CloneState.Running(file, index, total)
                    }).also { AppLog.info("Cloned $newPackage: ${it.apks.sumOf(File::length) / BYTES_IN_MB} MB") }
                } catch (e: Exception) {
                    AppLog.error("Cloning ${source.packageName} as $newPackage failed", e)
                    sheetOutput.deleteRecursively()
                    CloneState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun installResult() {
        val apks = (_cloneState.value as? CloneState.Done)?.apks ?: return
        installAndTrack(apks)
    }

    private fun installAndTrack(apks: List<File>) {
        if (_installing.value) return
        _installing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                install(apks)
            } finally {
                _installing.value = false
            }
        }
    }

    fun saveResult(uri: Uri) {
        val apks = (_cloneState.value as? CloneState.Done)?.apks ?: return
        save(apks, uri)
    }

    /** A single APK is written as is; an app with splits becomes an .apks archive of all parts. */
    private fun save(apks: List<File>, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                checkNotNull(output) { "Cannot open destination" }.use { out ->
                    if (apks.size == 1) {
                        apks.first().inputStream().use { it.copyTo(out, 1 shl 16) }
                    } else {
                        ZipOutputStream(out).use { zip ->
                            // The APKs are compressed already.
                            zip.setLevel(0)
                            for (apk in apks) {
                                zip.putNextEntry(ZipEntry(apk.name))
                                apk.inputStream().use { it.copyTo(zip, 1 shl 16) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
            }
            _events.tryEmit(
                result.fold(
                    { MainEvent.Message(R.string.status_saved) },
                    { MainEvent.Message(R.string.save_failed, it.message.orEmpty()) },
                )
            )
        }
    }

    /** [SplitMode.EXPORT] or [SplitMode.MERGE] for an app that is already installed. */
    fun selectSplitApp(app: ApkSource, mode: SplitMode) {
        if (_splitState.value is SplitState.Running) return
        openSplit(splitLoader.fromInstalled(app), mode)
    }

    /** Shared or opened into the app: the activity asks whether to install or merge them. */
    fun offerFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pendingFiles = uris
        _events.tryEmit(MainEvent.FilesReceived)
    }

    /** A bundle (APKS, XAPK, APKM, ZIP) or several loose split APKs, picked or shared into the app. */
    fun loadSplitFiles(uris: List<Uri>, mode: SplitMode) {
        pendingFiles = emptyList()
        if (uris.isEmpty() || _splitState.value is SplitState.Running) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val source = withContext(Dispatchers.IO) {
                runCatching { splitLoader.fromUris(uris, mergeInput, allowSingleApk = mode == SplitMode.INSTALL) }
            }
            _uiState.update { it.copy(loading = false) }
            source.onSuccess { openSplit(it, mode) }.onFailure { error ->
                withContext(Dispatchers.IO) { mergeInput.deleteRecursively() }
                _events.tryEmit(
                    if (error is NotSplitException) MainEvent.Message(R.string.merge_not_split)
                    else MainEvent.Message(R.string.merge_failed, error.message ?: error.javaClass.simpleName)
                )
            }
        }
    }

    private fun openSplit(source: SplitSource, mode: SplitMode) {
        splitSource = source
        splitMode = mode
        _splitState.value = SplitState.Idle
        _events.tryEmit(MainEvent.SplitSourceReady)
    }

    /** Installs the chosen splits as one session, re-signing them first if asked to. */
    fun startSplitInstall(selected: Set<String>, sign: Boolean, copyObb: Boolean) {
        val source = splitSource ?: return
        if (_splitState.value is SplitState.Running || _installing.value) return
        _splitState.value = SplitState.Running(SplitStep.EXTRACTING)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var files = source.materialize(selected, File(mergeWork, "splits")).map { it.second }
                if (sign) {
                    _splitState.value = SplitState.Running(SplitStep.SIGNING)
                    val signedDir = File(mergeWork, "signed").apply { deleteRecursively(); mkdirs() }
                    files = files.mapIndexed { index, file ->
                        File(signedDir, "$index.apk").also { cloner.sign(file, it) }
                    }
                }
                if (copyObb && source.obbEntries.isNotEmpty()) {
                    _splitState.value = SplitState.Running(SplitStep.COPYING_OBB)
                    source.copyObbFiles(Environment.getExternalStorageDirectory())
                }
                _splitState.value = SplitState.Idle
                _installing.value = true
                AppLog.info("Installing ${source.packageName}: ${files.size} files" + if (sign) ", re-signed" else "")
                install(files)
            } catch (e: Throwable) {
                AppLog.error("Installing ${source.packageName} from files failed", e)
                _splitState.value = SplitState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                _installing.value = false
                mergeWork.deleteRecursively()
            }
        }
    }

    /** Writes the chosen APKs of an installed app to [uri] as an .apks archive. */
    fun exportSplit(selected: Set<String>, uri: Uri) {
        val source = splitSource ?: return
        if (_splitState.value is SplitState.Running) return
        _splitState.value = SplitState.Running(SplitStep.EXPORTING)
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val files = source.materialize(selected, File(mergeWork, "splits"))
                val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                SplitExporter(getApplication()).export(source, files, checkNotNull(output) { "Cannot open destination" })
            }
            _splitState.value = SplitState.Idle
            result.fold(
                { AppLog.info("Exported ${source.packageName} as APKS (${selected.size} files)") },
                { AppLog.error("Exporting ${source.packageName} as APKS failed", it) },
            )
            _events.tryEmit(
                result.fold(
                    { MainEvent.Message(R.string.export_done) },
                    { MainEvent.Message(R.string.save_failed, it.message.orEmpty()) },
                )
            )
        }
    }

    fun startMerge(selected: Set<String>, sign: Boolean, force: Boolean) {
        val source = splitSource ?: return
        if (_splitState.value is SplitState.Running) return
        _splitState.value = SplitState.Running(SplitStep.EXTRACTING)
        viewModelScope.launch {
            _splitState.value = withContext(Dispatchers.IO) {
                try {
                    AppLog.info("Merging ${source.packageName}: ${selected.size} splits" + if (force) ", mismatches allowed" else "")
                    SplitState.Done(merger.merge(source, selected, sign, force, mergeWork) { step ->
                        _splitState.value = SplitState.Running(step)
                    }).also { AppLog.info("Merged ${source.packageName}: ${it.result.apk.length() / BYTES_IN_MB} MB") }
                } catch (e: SplitMismatchException) {
                    AppLog.warn("Merging ${source.packageName} refused: splits do not match the base (${e.splits.size})")
                    SplitState.Mismatch(e.splits.map { it.substringAfterLast('/') })
                } catch (e: TablesTooLargeException) {
                    AppLog.warn("Merging ${source.packageName} refused: resource tables of ${e.megabytes} MB do not fit in memory")
                    SplitState.TooLarge(e.megabytes)
                } catch (e: Throwable) {
                    AppLog.error("Merging ${source.packageName} failed", e)
                    // Malformed APKs can blow up deep inside the resource parser, not only with exceptions.
                    mergeWork.deleteRecursively()
                    SplitState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun installMerged() {
        val apk = (_splitState.value as? SplitState.Done)?.result?.apk ?: return
        installAndTrack(listOf(apk))
    }

    fun saveMerged(uri: Uri) {
        val apk = (_splitState.value as? SplitState.Done)?.result?.apk ?: return
        save(listOf(apk), uri)
    }

    /** Called when the merge sheet closes: nothing it produced or copied is kept. */
    fun discardSplit() {
        if (_splitState.value is SplitState.Running) return
        _splitState.value = SplitState.Idle
        splitSource = null
        pendingFiles = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            mergeWork.deleteRecursively()
            mergeInput.deleteRecursively()
        }
    }

    /** Called when the clone sheet closes: nothing it produced is kept. */
    fun discardResult() {
        if (_cloneState.value is CloneState.Running) return
        _cloneState.value = CloneState.Idle
        selected = null
        viewModelScope.launch(Dispatchers.IO) {
            sheetOutput.deleteRecursively()
            pickedApk.delete()
        }
    }

    fun updateClone(clone: CloneInfo) = updateClones(listOf(clone))

    /** Brings every outdated clone up to date, one after another. */
    /**
     * The Refresh of the Clones side: looks at every clone and its original again and updates
     * the clones that are behind. Says so when there is nothing to do.
     */
    fun refreshClones() {
        if (_uiState.value.updatingClone != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val installed = withContext(Dispatchers.IO) { repository.installed() }
            allApps = installed.apps
            allClones = installed.clones
            _uiState.update { it.copy(loading = false) }
            publishApps()
            val outdated = allClones.filter { it.updateAvailable }
            AppLog.info("Clones checked: ${allClones.size}, behind: ${outdated.size}")
            if (outdated.isEmpty()) _events.tryEmit(MainEvent.Message(R.string.clones_up_to_date)) else updateClones(outdated)
        }
    }

    private fun updateClones(clones: List<CloneInfo>) {
        if (clones.isEmpty() || _uiState.value.updatingClone != null) return
        viewModelScope.launch(Dispatchers.IO) {
            for (clone in clones) {
                val request = clone.updateRequest() ?: continue
                _uiState.update { it.copy(updatingClone = clone.app.packageName) }
                try {
                    AppLog.info("Updating clone ${clone.app.packageName}")
                    install(cloner.clone(request, updateOutput) { _, _, _ -> })
                } catch (e: Exception) {
                    AppLog.error("Updating clone ${clone.app.packageName} failed", e)
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
            val installed = withContext(Dispatchers.IO) { repository.installed() }
            loaded = true
            allApps = installed.apps
            allClones = installed.clones
            _uiState.update { it.copy(loading = false) }
            publishApps()
        }
    }

    /** Returns once the install has actually ended, so progress can be shown for all of it. */
    private suspend fun install(apks: List<File>) = coroutineScope {
        val context = getApplication<Application>()
        // Subscribed before the install starts: a silent update can finish before we get to wait.
        val finished = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(INSTALL_TIMEOUT_MS) { InstallReceiver.sessionFinished.first() }
        }
        when (val outcome = Installer.choose(context, background = false).install(context, apks)) {
            // The system installer reports its own result later, as a toast.
            InstallOutcome.Pending -> finished.await()
            InstallOutcome.Success -> Unit
            is InstallOutcome.Failed -> {
                AppLog.error("Install failed: ${outcome.reason}")
                _events.tryEmit(MainEvent.Message(R.string.install_failed, outcome.reason))
            }
        }
        finished.cancel()
    }

    private fun publishApps() {
        fun matches(app: ApkSource) =
            query.isEmpty() || app.label.contains(query, true) || app.packageName.contains(query, true)
        _uiState.update { state ->
            state.copy(
                apps = allApps.filter { (showSystem || !it.isSystem) && matches(it) },
                splitApps = allApps.filter { it.apkPaths.size > 1 && (showSystem || !it.isSystem) && matches(it) },
                clones = allClones.filter { matches(it.app) },
                outdatedClones = allClones.count { it.updateAvailable },
            )
        }
    }
}
