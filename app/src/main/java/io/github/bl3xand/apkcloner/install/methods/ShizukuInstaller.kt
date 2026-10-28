package io.github.bl3xand.apkcloner.install.methods

import android.content.Context
import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.install.Installer
import io.github.bl3xand.apkcloner.install.InstallOutcome
import io.github.bl3xand.apkcloner.install.InstallReceiver
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.shizuku.ShizukuBridge
import java.io.File

/** Installs with shell privileges through Shizuku: never asks, and reports the result right away. */
object ShizukuInstaller : Installer {

    override fun canInstallSilently(context: Context, app: ApkSource) = true

    override suspend fun install(
        context: Context, apks: List<File>, background: Boolean, label: String, installerPackage: String?,
    ): InstallOutcome {
        AppLog.info("Shizuku install: ${label.ifBlank { apks.firstOrNull()?.name ?: "?" }} (${apks.size} file(s))")
        val error = ShizukuBridge.install(apks, installerPackage ?: context.packageName)
        if (error == null) {
            AppLog.info("Shizuku install: success")
            return InstallOutcome.Success.also { InstallReceiver.notifyFinished() }
        }
        AppLog.error("Shizuku install failed: $error")
        return InstallOutcome.Failed(error)
    }
}