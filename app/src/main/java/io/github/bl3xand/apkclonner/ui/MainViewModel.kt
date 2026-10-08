package io.github.bl3xand.apkclonner.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bl3xand.apkclonner.clone.ApkCloner
import io.github.bl3xand.apkclonner.clone.CloneRequest
import io.github.bl3xand.apkclonner.clone.SignatureMode
import io.github.bl3xand.apkclonner.data.ApkSource
import io.github.bl3xand.apkclonner.data.AppRepository
import java.io.File
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
    val loading: Boolean = false,
    val apps: List<ApkSource> = emptyList(),
)

sealed interface CloneState {
    data object Idle : CloneState
    data class Running(val file: String, val index: Int, val total: Int) : CloneState
    data class Done(val apks: List<File>) : CloneState
    data class Failed(val message: String) : CloneState
}

sealed interface MainEvent {
    data class SourceReady(val source: ApkSource) : MainEvent
    data object InvalidApk : MainEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository(application)
    private val cloner = ApkCloner(application)

    private var allApps: List<ApkSource> = emptyList()
    private var query = ""
    private var showSystem = false

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _cloneState = MutableStateFlow<CloneState>(CloneState.Idle)
    val cloneState: StateFlow<CloneState> = _cloneState.asStateFlow()

    private val _events = MutableSharedFlow<MainEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<MainEvent> = _events.asSharedFlow()

    /** The source the clone sheet is currently configured for. */
    var selected: ApkSource? = null
        private set

    fun setFileAccess(granted: Boolean) {
        _uiState.update { it.copy(hasFileAccess = granted) }
        if (granted && allApps.isEmpty() && !_uiState.value.loading) loadApps()
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
        selected = source
        _cloneState.value = CloneState.Idle
        _events.tryEmit(MainEvent.SourceReady(source))
    }

    fun loadApkFile(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val source = withContext(Dispatchers.IO) { runCatching { repository.fromUri(uri) }.getOrNull() }
            _uiState.update { it.copy(loading = false) }
            if (source != null) select(source) else _events.tryEmit(MainEvent.InvalidApk)
        }
    }

    fun startClone(newPackage: String, newLabel: String, signature: SignatureMode) {
        val source = selected ?: return
        if (_cloneState.value is CloneState.Running) return
        _cloneState.value = CloneState.Running(source.label, 0, source.apkPaths.size)
        viewModelScope.launch {
            _cloneState.value = withContext(Dispatchers.IO) {
                try {
                    val request = CloneRequest(
                        apks = source.apkPaths.map(::File),
                        newPackage = newPackage,
                        newLabel = newLabel.takeIf { it != source.label },
                        signature = signature,
                    )
                    CloneState.Done(cloner.clone(request) { file, index, total ->
                        _cloneState.value = CloneState.Running(file, index, total)
                    })
                } catch (e: Exception) {
                    CloneState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun loadApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            allApps = withContext(Dispatchers.IO) { repository.installedApps() }
            _uiState.update { it.copy(loading = false) }
            publishApps()
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
