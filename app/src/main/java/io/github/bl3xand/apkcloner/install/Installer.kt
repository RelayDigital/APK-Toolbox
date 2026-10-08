package io.github.bl3xand.apkcloner.install

import android.content.Context
import android.os.Build
import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.settings.AppSettings
import io.github.bl3xand.apkcloner.shizuku.ShizukuBridge
import java.io.File

sealed interface InstallOutcome {
    data object Success : InstallOutcome
    data class Failed(val reason: String) : InstallOutcome

    /** Handed to the system; the result arrives later through [InstallReceiver]. */
    data object Pending : InstallOutcome
}

interface Installer {

    /** Whether updating the installed [app] is expected to go through without asking the user. */
    fun canInstallSilently(context: Context, app: ApkSource): Boolean

    /** [apks] is the base APK plus splits. [background] and [label] only matter for [InstallOutcome.Pending]. */
    suspend fun install(context: Context, apks: List<File>, background: Boolean = false, label: String = ""): InstallOutcome

    companion object {
        /** The method chosen in settings, falling back to the system installer when Shizuku is not usable. */
        fun current(context: Context): Installer =
            if (AppSettings(context).installMethod == AppSettings.InstallMethod.SHIZUKU &&
                ShizukuBridge.state() == ShizukuBridge.State.READY
            ) ShizukuInstaller else StockInstaller
    }
}

/** The system's session installer. Silent only under the platform's own conditions. */
object StockInstaller : Installer {

    override fun canInstallSilently(context: Context, app: ApkSource): Boolean {
        val installer = runCatching {
            context.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
        }.getOrNull()
        // The system skips the confirmation only for the installer of record, and only for apps
        // that target a recent enough SDK.
        return installer == context.packageName &&
            app.appInfo.targetSdkVersion >= Build.VERSION.SDK_INT - MAX_TARGET_SDK_LAG
    }

    override suspend fun install(context: Context, apks: List<File>, background: Boolean, label: String): InstallOutcome =
        try {
            ApkInstaller.install(context, apks, background, label)
            InstallOutcome.Pending
        } catch (e: Exception) {
            InstallOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }

    private const val MAX_TARGET_SDK_LAG = 3
}

/** Installs with shell privileges through Shizuku: never asks, and reports the result right away. */
object ShizukuInstaller : Installer {

    override fun canInstallSilently(context: Context, app: ApkSource) = true

    override suspend fun install(context: Context, apks: List<File>, background: Boolean, label: String): InstallOutcome {
        val error = ShizukuBridge.install(apks) ?: return InstallOutcome.Success.also { InstallReceiver.notifyFinished() }
        return InstallOutcome.Failed(error)
    }
}
