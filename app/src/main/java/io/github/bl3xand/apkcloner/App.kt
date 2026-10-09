package io.github.bl3xand.apkcloner

import android.app.Application
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.telegram.Telegram
import io.github.bl3xand.apkcloner.sources.telegram.TelegramClient
import io.github.bl3xand.apkcloner.ui.Messages
import java.io.File
import kotlin.concurrent.thread

/** Sets the log up before anything else runs, so every part of the app can write to it. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        TelegramClient.attach(this)
        Telegram.gateway = TelegramClient
        TelegramClient.onSessionEnded = { Messages.show(this, Tr.get("telegramErrSession")) }
        clearDownloads()
        checkTelegram()
        AppLog.info("APK Toolbox ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started")
        // A crash is the one thing the log must not miss; the system still gets to handle it.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.error("Crashed on ${thread.name}:\n${error.stackTraceToString().take(MAX_TRACE)}")
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * What was downloaded and not installed - whole files and parts of cancelled ones - is kept
     * while the app runs, so that an install can be tried again at once. A new start begins with
     * nothing, or the cache would only ever grow.
     */
    private fun clearDownloads() {
        thread(name = "clear-downloads") {
            runCatching {
                File(externalCacheDir ?: cacheDir, DOWNLOADS_DIR).listFiles()?.forEach { it.deleteRecursively() }
                TelegramClient.downloadsDir(this).deleteRecursively()
            }
        }
    }

    /** A session left by an earlier run is opened and asked whether it still holds. */
    private fun checkTelegram() {
        if (!TelegramClient.hasSession) return
        thread(name = "telegram-check") { TelegramClient.isSignedIn }
    }

    private companion object {
        /** The folder of the sources' downloads; the same one SourcesRepository.apkDir names. */
        const val DOWNLOADS_DIR = "sources"

        const val MAX_TRACE = 4000
    }
}
