package io.github.bl3xand.apkclonner.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bl3xand.apkclonner.R
import io.github.bl3xand.apkclonner.clone.ApkCloner
import io.github.bl3xand.apkclonner.clone.CloneRequest
import io.github.bl3xand.apkclonner.data.ApkSource
import io.github.bl3xand.apkclonner.data.AppRepository
import io.github.bl3xand.apkclonner.data.CloneInfo
import io.github.bl3xand.apkclonner.install.ApkInstaller
import io.github.bl3xand.apkclonner.install.InstallReceiver
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val cloner = ApkCloner(application)
    private val repository = AppRepository(application, cloner)

    // Everything this app writes on its own lives here and is wiped as soon as it is not needed.
    private val tempDir = File(application.cacheDir, "work")
    private val pickedApk = File(tempDir, "picked.apk")
    private val sheetOutput = File(tempDir, "clone")
    private val updateOutput = File(tempDir, "update")

    private var allApps: List<ApkSource> = emptyList()
    private var query = ""
    private var showSystem = false
    private var loaded = false

    /** Which tab is showing; kept here so it survives rotation. */
    var tab = 0

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _cloneState = MutableStateFlow<CloneState>(CloneState.Idle)
    val cloneState: StateFlow<CloneState> = _cloneState.asStateFlow()

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
                    CloneState.Done(cloner.clone(request(source, newPackage, newLabel), sheetOutput) { file, index, total ->
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
        viewModelScope.launch(Dispatchers.IO) { install(apks) }
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

    /**
     * Re-clones the original under the clone's package name and installs it over the old clone.
     * Both are signed with the same key, so the system treats it as a normal update.
     */
    fun updateClone(clone: CloneInfo) {
        val original = clone.original ?: return
        if (_uiState.value.updatingClone != null) return
        _uiState.update { it.copy(updatingClone = clone.app.packageName) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apks = cloner.clone(request(original, clone.app.packageName, clone.app.label), updateOutput) { _, _, _ -> }
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
            _uiState.update { it.copy(loading = false, clones = installed.clones) }
            publishApps()
        }
    }

    private fun request(source: ApkSource, newPackage: String, newLabel: String) = CloneRequest(
        apks = source.apkPaths.map(::File),
        newPackage = newPackage,
        newLabel = newLabel.takeIf { it != source.label },
    )

    private fun install(apks: List<File>) {
        try {
            ApkInstaller.install(getApplication(), apks)
        } catch (e: Exception) {
            _events.tryEmit(MainEvent.Message(R.string.install_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun publishApps() {
        val filtered = allApps.filter { app ->
            (showSystem || !app.isSystem) &&
                (query.isEmpty() || app.label.contains(query, true) || app.packageName.contains(query, true))
        }
        _uiState.update { it.copy(apps = filtered) }
    }
}
