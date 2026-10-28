package io.github.bl3xand.apkcloner.install.methods

import android.content.Context
import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.install.Installer
import io.github.bl3xand.apkcloner.install.InstallOutcome
import io.github.bl3xand.apkcloner.install.InstallReceiver
import io.github.bl3xand.apkcloner.log.AppLog
import java.io.File

/** Experimental: installs with `su`. Never asks, and reports the result right away. */
object RootInstaller : Installer {

    override fun canInstallSilently(context: Context, app: ApkSource) = true

    override suspend fun install(
        context: Context, apks: List<File>, background: Boolean, label: String, installerPackage: String?,
    ): InstallOutcome {
        AppLog.info("Root install: ${label.ifBlank { apks.firstOrNull()?.name ?: "?" }} (${apks.size} file(s))")
        val error = Root.install(apks, installerPackage)
        if (error == null) {
            AppLog.info("Root install: success")
            return InstallOutcome.Success.also { InstallReceiver.notifyFinished() }
        }
        AppLog.error("Root install failed: $error")
        return InstallOutcome.Failed(error)
    }
}
