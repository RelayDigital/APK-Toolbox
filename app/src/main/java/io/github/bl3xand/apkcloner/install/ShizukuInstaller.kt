package io.github.bl3xand.apkcloner.install

import android.content.Context
import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.shizuku.ShizukuBridge
import java.io.File

/** Installs with shell privileges through Shizuku: never asks, and reports the result right away. */
object ShizukuInstaller : Installer {

    override fun canInstallSilently(context: Context, app: ApkSource) = true

    override suspend fun install(
        context: Context, apks: List<File>, background: Boolean, label: String, installerPackage: String?,
    ): InstallOutcome {
        val error = ShizukuBridge.install(apks, installerPackage ?: context.packageName)
            ?: return InstallOutcome.Success.also { InstallReceiver.notifyFinished() }
        return InstallOutcome.Failed(error)
    }
}
