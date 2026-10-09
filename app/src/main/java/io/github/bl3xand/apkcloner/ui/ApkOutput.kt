package io.github.bl3xand.apkcloner.ui

import android.app.Application
import android.net.Uri
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.install.InstallOutcome
import io.github.bl3xand.apkcloner.install.InstallReceiver
import io.github.bl3xand.apkcloner.install.Installer
import io.github.bl3xand.apkcloner.log.AppLog
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the APKs the tools of this app produce end up: installed, or saved to a place of the
 * user's choosing. Cloning, merging and installing splits all hand their result over here.
 */
class ApkOutput(
    private val application: Application,
    private val scope: CoroutineScope,
    private val events: MutableSharedFlow<MainEvent>,
) {
    private val _installing = MutableStateFlow(false)

    /** True while the result of a sheet is being installed. */
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    /** Installs [apks] in the background, unless an install is running already. */
    fun installInBackground(apks: List<File>) {
        if (_installing.value) return
        // Set before the coroutine starts, so a second tap finds it taken.
        _installing.value = true
        scope.launch(Dispatchers.IO) { installTracked(apks) }
    }

    /** Installs [apks] and keeps [installing] set for the whole of it. */
    suspend fun installTracked(apks: List<File>) {
        _installing.value = true
        try {
            install(apks)
        } finally {
            _installing.value = false
        }
    }

    /** Returns once the install has actually ended, so progress can be shown for all of it. */
    suspend fun install(apks: List<File>) = coroutineScope {
        // Subscribed before the install starts: a silent update can finish before we get to wait.
        val finished = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(INSTALL_TIMEOUT_MS) { InstallReceiver.sessionFinished.first() }
        }
        when (val outcome = Installer.choose(application, background = false).install(application, apks)) {
            // The system installer reports its own result later, as a toast.
            InstallOutcome.Pending -> finished.await()
            InstallOutcome.Success -> Unit
            is InstallOutcome.Failed -> {
                AppLog.error("Install failed: ${outcome.reason}")
                events.tryEmit(MainEvent.Message(R.string.install_failed, outcome.reason))
            }
        }
        finished.cancel()
    }

    /** A single APK is written as is; an app with splits becomes an .apks archive of all parts. */
    fun save(apks: List<File>, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val output = application.contentResolver.openOutputStream(uri, "wt")
                checkNotNull(output) { "Cannot open destination" }.use { out ->
                    if (apks.size == 1) {
                        apks.first().inputStream().use { it.copyTo(out, COPY_BUFFER) }
                    } else {
                        ZipOutputStream(out).use { zip ->
                            // The APKs are compressed already.
                            zip.setLevel(0)
                            for (apk in apks) {
                                zip.putNextEntry(ZipEntry(apk.name))
                                apk.inputStream().use { it.copyTo(zip, COPY_BUFFER) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
            }
            result.fold(
                { AppLog.info("Saved ${apks.size} APK file(s) to a place the user chose") },
                { AppLog.error("Saving the result failed", it) },
            )
            events.tryEmit(
                result.fold(
                    { MainEvent.Message(R.string.status_saved) },
                    { MainEvent.Message(R.string.save_failed, it.message.orEmpty()) },
                )
            )
        }
    }

    companion object {
        const val BYTES_IN_MB = 1024 * 1024

        /** How long to keep showing progress for an install the system never reports back on. */
        private const val INSTALL_TIMEOUT_MS = 120_000L
        private const val COPY_BUFFER = 1 shl 16
    }
}
