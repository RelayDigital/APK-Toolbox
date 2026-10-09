package io.github.bl3xand.apkcloner

import android.app.Application
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.sources.telegram.Telegram
import io.github.bl3xand.apkcloner.sources.telegram.TelegramClient

/** Sets the log up before anything else runs, so every part of the app can write to it. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        TelegramClient.attach(this)
        Telegram.gateway = TelegramClient
        AppLog.info("APK Toolbox ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started")
        // A crash is the one thing the log must not miss; the system still gets to handle it.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.error("Crashed on ${thread.name}:\n${error.stackTraceToString().take(MAX_TRACE)}")
            previous?.uncaughtException(thread, error)
        }
    }

    private companion object {
        const val MAX_TRACE = 4000
    }
}
