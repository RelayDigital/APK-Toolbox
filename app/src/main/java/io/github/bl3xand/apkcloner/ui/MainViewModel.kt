package io.github.bl3xand.apkcloner.ui

import android.app.Application
import android.net.Uri
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
    /** Package of the clone currently being rebuilt for an update. */
    val updatingClone: String? = null,
) {
    val permissionsGranted: Boolean get() = hasFileAccess && canInstall
}

sealed interface CloneState {
    data object Idle : CloneState
    data class Running(val file: String, val index: Int, val total: Int) : CloneState
    data class Done(val apks: List<File>) : CloneState
    data class Failed(val message: String) : CloneState
}

sealed interface MainEvent {
    data class SourceReady(val source: ApkSource) : MainEvent
    data class Message(@StringRes val text: Int, val argument: String = "") : MainEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        /** How long to keep showing progress for an install the system never reports back on. */
        const val INSTALL_TIMEOUT_MS = 120_000L
    }

    private val cloner = ApkCloner(application)
    private val repository = AppRepository(application, cloner)

    // Everything this app writes on its own lives here and is wiped as soon as it is not needed.
    private val tempDir = File(application.cacheDir, "work")
    private val pickedApk = File(tempDir, "picked.apk")
    private val sheetOutput = File(tempDir, "clone")
    private val updateOutput = File(tempDir, "update")

    private var allApps: List<ApkSource> = emptyList()
    private var allClones: List<CloneInfo> = emptyList()
    private var query = ""
    private var showSystem = false
    private var loaded = false

    /** Which tab is showing; kept here so it survives rotation. */
    var tab = 0

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _cloneState = MutableStateFlow<CloneState>(CloneState.Idle)
    val cloneState: StateFlow<CloneState> = _cloneState.asStateFlow()

    private val _installing = MutableStateFlow(false)

    /** True while the result of the clone sheet is being installed. */
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    private val _events = MutableSharedFlow<MainEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MainEvent> = _events.asSharedFlow()

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

    fun startClone(newPackage: String, newLabel: String) {
        val source = selected ?: return
        if (_cloneState.value is CloneState.Running) return
        _cloneState.value = CloneState.Running(source.label, 0, source.apkPaths.size)
        viewModelScope.launch {
            _cloneState.value = withContext(Dispatchers.IO) {
                try {
                    CloneState.Done(cloner.clone(source.cloneRequest(newPackage, newLabel), sheetOutput) { file, index, total ->
                        _cloneState.value = CloneState.Running(file, index, total)
                    })
                } catch (e: Exception) {
                    sheetOutput.deleteRecursively()
                    CloneState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun installResult() {
        val apks = (_cloneState.value as? CloneState.Done)?.apks ?: return
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

    /** A single APK is written as is; an app with splits becomes an .apks archive of all parts. */
    fun saveResult(uri: Uri) {
        val apks = (_cloneState.value as? CloneState.Done)?.apks ?: return
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

    fun updateClone(clone: CloneInfo) {
        val request = clone.updateRequest() ?: return
        if (_uiState.value.updatingClone != null) return
        _uiState.update { it.copy(updatingClone = clone.app.packageName) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apks = cloner.clone(request, updateOutput) { _, _, _ -> }
                install(apks)
            } catch (e: Exception) {
                _events.tryEmit(MainEvent.Message(R.string.status_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                // The installer session holds its own copy by now.
                updateOutput.deleteRecursively()
                _uiState.update { it.copy(updatingClone = null) }
            }
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
        when (val outcome = Installer.current(context).install(context, apks)) {
            // The system installer reports its own result later, as a toast.
            InstallOutcome.Pending -> finished.await()
            InstallOutcome.Success -> _events.tryEmit(MainEvent.Message(R.string.install_success))
            is InstallOutcome.Failed -> _events.tryEmit(MainEvent.Message(R.string.install_failed, outcome.reason))
        }
        finished.cancel()
    }

    private fun publishApps() {
        fun matches(app: ApkSource) =
            query.isEmpty() || app.label.contains(query, true) || app.packageName.contains(query, true)
        _uiState.update { state ->
            state.copy(
                apps = allApps.filter { (showSystem || !it.isSystem) && matches(it) },
                clones = allClones.filter { matches(it.app) },
            )
        }
    }
}
