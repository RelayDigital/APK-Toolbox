package io.github.bl3xand.apkcloner.install

import android.content.Context
import io.github.bl3xand.apkcloner.data.ApkSource
import java.io.File

/** Experimental: installs with `su`. Never asks, and reports the result right away. */
object RootInstaller : Installer {

    override fun canInstallSilently(context: Context, app: ApkSource) = true

    override suspend fun install(
        context: Context, apks: List<File>, background: Boolean, label: String, installerPackage: String?,
    ): InstallOutcome {
        val error = Root.install(apks, installerPackage)
            ?: return InstallOutcome.Success.also { InstallReceiver.notifyFinished() }
        return InstallOutcome.Failed(error)
    }
}
