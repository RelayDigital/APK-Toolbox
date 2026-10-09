package io.github.bl3xand.apkcloner.ui

import android.app.Application
import android.net.Uri
import android.os.Environment
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.clone.ApkCloner
import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.merge.NotSplitException
import io.github.bl3xand.apkcloner.merge.SplitExporter
import io.github.bl3xand.apkcloner.merge.SplitLoader
import io.github.bl3xand.apkcloner.merge.SplitMerger
import io.github.bl3xand.apkcloner.merge.SplitMismatchException
import io.github.bl3xand.apkcloner.merge.SplitSource
import io.github.bl3xand.apkcloner.merge.SplitStep
import io.github.bl3xand.apkcloner.merge.TablesTooLargeException
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sets of split APKs: installing them, exporting them as an archive and merging them into one APK. */
class SplitController(
    private val application: Application,
    private val scope: CoroutineScope,
    private val cloner: ApkCloner,
    tempDir: File,
    private val output: ApkOutput,
    private val events: MutableSharedFlow<MainEvent>,
    private val setLoading: (Boolean) -> Unit,
) {
    private val mergeInput = File(tempDir, "merge-in")
    private val mergeWork = File(tempDir, "merge")
    private val loader = SplitLoader(application)
    private val merger = SplitMerger(cloner)

    private val _state = MutableStateFlow<SplitState>(SplitState.Idle)
    val state: StateFlow<SplitState> = _state.asStateFlow()

    /** The APK set the split sheet is currently showing, and what it is there for. */
    var source: SplitSource? = null
        private set
    var mode = SplitMode.MERGE
        private set

    /** Files that arrived from another app, waiting for the user to say what to do with them. */
    var pendingFiles: List<Uri> = emptyList()
        private set

    /** [SplitMode.EXPORT] or [SplitMode.MERGE] for an app that is already installed. */
    fun selectApp(app: ApkSource, mode: SplitMode) {
        if (_state.value is SplitState.Running) return
        AppLog.debug("Opened the splits of ${app.packageName} for $mode")
        open(loader.fromInstalled(app), mode)
    }

    /** Shared or opened into the app: the activity asks whether to install or merge them. */
    fun offerFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pendingFiles = uris
        events.tryEmit(MainEvent.FilesReceived)
    }

    /** A bundle (APKS, XAPK, APKM, ZIP) or several loose split APKs, picked or shared into the app. */
    fun loadFiles(uris: List<Uri>, mode: SplitMode) {
        pendingFiles = emptyList()
        if (uris.isEmpty() || _state.value is SplitState.Running) return
        scope.launch {
            setLoading(true)
            val loaded = withContext(Dispatchers.IO) {
                runCatching { loader.fromUris(uris, mergeInput, allowSingleApk = mode == SplitMode.INSTALL) }
            }
            setLoading(false)
            loaded.onSuccess {
                AppLog.info("Loaded ${uris.size} file(s) for $mode: ${it.packageName}")
                open(it, mode)
            }.onFailure { error ->
                AppLog.warn("Could not read ${uris.size} file(s) for $mode: ${error.message ?: error.javaClass.simpleName}")
                withContext(Dispatchers.IO) { mergeInput.deleteRecursively() }
                events.tryEmit(
                    if (error is NotSplitException) MainEvent.Message(R.string.merge_not_split)
                    else MainEvent.Message(R.string.merge_failed, error.message ?: error.javaClass.simpleName)
                )
            }
        }
    }

    private fun open(source: SplitSource, mode: SplitMode) {
        this.source = source
        this.mode = mode
        _state.value = SplitState.Idle
        events.tryEmit(MainEvent.SplitSourceReady)
    }

    /** Installs the chosen splits as one session, re-signing them first if asked to. */
    fun startInstall(selected: Set<String>, sign: Boolean, copyObb: Boolean) {
        val source = source ?: return
        if (_state.value is SplitState.Running || output.installing.value) return
        _state.value = SplitState.Running(SplitStep.EXTRACTING)
        scope.launch(Dispatchers.IO) {
            try {
                AppLog.debug("Preparing ${selected.size} file(s) of ${source.packageName} for install")
                var files = source.materialize(selected, File(mergeWork, "splits")).map { it.second }
                if (sign) {
                    _state.value = SplitState.Running(SplitStep.SIGNING)
                    val signedDir = File(mergeWork, "signed").apply { deleteRecursively(); mkdirs() }
                    files = files.mapIndexed { index, file ->
                        File(signedDir, "$index.apk").also { cloner.sign(file, it) }
                    }
                }
                if (copyObb && source.obbEntries.isNotEmpty()) {
                    _state.value = SplitState.Running(SplitStep.COPYING_OBB)
                    source.copyObbFiles(Environment.getExternalStorageDirectory())
                }
                _state.value = SplitState.Idle
                AppLog.info("Installing ${source.packageName}: ${files.size} files" + if (sign) ", re-signed" else "")
                output.installTracked(files)
            } catch (e: Throwable) {
                AppLog.error("Installing ${source.packageName} from files failed", e)
                _state.value = SplitState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                mergeWork.deleteRecursively()
            }
        }
    }

    /** Writes the chosen APKs of an installed app to [uri] as an .apks archive. */
    fun export(selected: Set<String>, uri: Uri) {
        val source = source ?: return
        if (_state.value is SplitState.Running) return
        _state.value = SplitState.Running(SplitStep.EXPORTING)
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val files = source.materialize(selected, File(mergeWork, "splits"))
                val stream = application.contentResolver.openOutputStream(uri, "wt")
                SplitExporter(application).export(source, files, checkNotNull(stream) { "Cannot open destination" })
            }
            _state.value = SplitState.Idle
            result.fold(
                { AppLog.info("Exported ${source.packageName} as APKS (${selected.size} files)") },
                { AppLog.error("Exporting ${source.packageName} as APKS failed", it) },
            )
            events.tryEmit(
                result.fold(
                    { MainEvent.Message(R.string.export_done) },
                    { MainEvent.Message(R.string.save_failed, it.message.orEmpty()) },
                )
            )
        }
    }

    fun startMerge(selected: Set<String>, sign: Boolean, force: Boolean) {
        val source = source ?: return
        if (_state.value is SplitState.Running) return
        _state.value = SplitState.Running(SplitStep.EXTRACTING)
        scope.launch {
            _state.value = withContext(Dispatchers.IO) {
                try {
                    AppLog.info("Merging ${source.packageName}: ${selected.size} splits" + if (force) ", mismatches allowed" else "")
                    SplitState.Done(merger.merge(source, selected, sign, force, mergeWork) { step ->
                        AppLog.debug("Merging ${source.packageName}: $step")
                        _state.value = SplitState.Running(step)
                    }).also { AppLog.info("Merged ${source.packageName}: ${it.result.apk.length() / ApkOutput.BYTES_IN_MB} MB") }
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
        val apk = (_state.value as? SplitState.Done)?.result?.apk ?: return
        output.installInBackground(listOf(apk))
    }

    fun saveMerged(uri: Uri) {
        val apk = (_state.value as? SplitState.Done)?.result?.apk ?: return
        output.save(listOf(apk), uri)
    }

    /** Called when the split sheet closes: nothing it produced or copied is kept. */
    fun discard() {
        if (_state.value is SplitState.Running) return
        _state.value = SplitState.Idle
        source = null
        pendingFiles = emptyList()
        scope.launch(Dispatchers.IO) {
            mergeWork.deleteRecursively()
            mergeInput.deleteRecursively()
        }
    }
}
