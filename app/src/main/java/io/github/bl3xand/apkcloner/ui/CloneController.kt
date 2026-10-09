package io.github.bl3xand.apkcloner.ui

import android.net.Uri
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.clone.ApkCloner
import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.data.AppRepository
import io.github.bl3xand.apkcloner.log.AppLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Making a new clone: everything the clone sheet does, from picking the app to the result. */
class CloneController(
    private val scope: CoroutineScope,
    private val cloner: ApkCloner,
    private val repository: AppRepository,
    private val tempDir: File,
    private val output: ApkOutput,
    private val events: MutableSharedFlow<MainEvent>,
    private val setLoading: (Boolean) -> Unit,
    private val installedApps: () -> List<ApkSource>,
) {
    private val pickedApk = File(tempDir, "picked.apk")
    private val sheetOutput = File(tempDir, "clone")

    private val _state = MutableStateFlow<CloneState>(CloneState.Idle)
    val state: StateFlow<CloneState> = _state.asStateFlow()

    /** The source the clone sheet is currently configured for. */
    var selected: ApkSource? = null
        private set

    /** Permissions unticked for the clone being made; forgotten when another app is picked. */
    var removedPermissions: Set<String> = emptySet()

    /**
     * Package and app name to offer for a new clone of [source]: `.clone` and "Clone", or the
     * first numbered variant that is not installed yet, so an app can be cloned more than once.
     */
    fun suggest(source: ApkSource): Pair<String, String> {
        val taken = installedApps().mapTo(HashSet()) { it.packageName }
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
        if (_state.value is CloneState.Running) return
        selected = source
        removedPermissions = emptySet()
        _state.value = CloneState.Idle
        events.tryEmit(MainEvent.SourceReady(source))
    }

    /** An APK picked from storage instead of an installed app. */
    fun loadFile(uri: Uri) {
        scope.launch {
            setLoading(true)
            val source = withContext(Dispatchers.IO) {
                tempDir.mkdirs()
                runCatching { repository.fromUri(uri, pickedApk) }.getOrNull()
            }
            setLoading(false)
            if (source != null) {
                select(source)
            } else {
                AppLog.warn("The picked file is not an APK that can be cloned")
                pickedApk.delete()
                events.tryEmit(MainEvent.Message(R.string.error_invalid_apk))
            }
        }
    }

    fun start(newPackage: String, newLabel: String, badgeIcon: Boolean) {
        val source = selected ?: return
        if (_state.value is CloneState.Running) return
        _state.value = CloneState.Running(source.label, 0, source.apkPaths.size)
        scope.launch {
            _state.value = withContext(Dispatchers.IO) {
                try {
                    AppLog.info("Cloning ${source.packageName} ${source.versionName.orEmpty()} as $newPackage (${source.apkPaths.size} files)")
                    if (removedPermissions.isNotEmpty()) AppLog.info("Clone is made without: ${removedPermissions.joinToString()}")
                    val request = source.cloneRequest(newPackage, newLabel, badgeIcon, removedPermissions = removedPermissions)
                    CloneState.Done(cloner.clone(request, sheetOutput) { file, index, total ->
                        _state.value = CloneState.Running(file, index, total)
                    }).also { AppLog.info("Cloned $newPackage: ${it.apks.sumOf(File::length) / ApkOutput.BYTES_IN_MB} MB") }
                } catch (e: Exception) {
                    AppLog.error("Cloning ${source.packageName} as $newPackage failed", e)
                    sheetOutput.deleteRecursively()
                    CloneState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun installResult() {
        val apks = (_state.value as? CloneState.Done)?.apks ?: return
        output.installInBackground(apks)
    }

    fun saveResult(uri: Uri) {
        val apks = (_state.value as? CloneState.Done)?.apks ?: return
        output.save(apks, uri)
    }

    /** Called when the clone sheet closes: nothing it produced is kept. */
    fun discard() {
        if (_state.value is CloneState.Running) return
        _state.value = CloneState.Idle
        selected = null
        scope.launch(Dispatchers.IO) {
            sheetOutput.deleteRecursively()
            pickedApk.delete()
        }
    }
}
