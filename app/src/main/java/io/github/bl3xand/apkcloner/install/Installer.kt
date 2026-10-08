package io.github.bl3xand.apkcloner.install

import android.content.Context
import android.os.Build
import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.settings.AppSettings
import io.github.bl3xand.apkcloner.shizuku.ShizukuBridge
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface InstallOutcome {
    data object Success : InstallOutcome
    data class Failed(val reason: String) : InstallOutcome

    /** Handed to the system; the result arrives later through [InstallReceiver]. */
    data object Pending : InstallOutcome
}

interface Installer {

    /** Whether updating the installed [app] is expected to go through without asking the user. */
    fun canInstallSilently(context: Context, app: ApkSource): Boolean

    /**
     * [apks] is the base APK plus splits. [background] and [label] only matter for
     * [InstallOutcome.Pending]. [installerPackage] names another installer of record where the
     * method allows it; the system installer always records this app.
     */
    suspend fun install(
        context: Context,
        apks: List<File>,
        background: Boolean = false,
        label: String = "",
        installerPackage: String? = null,
    ): InstallOutcome

    companion object {
        /** Recorded as the installer for apps that only work when installed from Google Play. */
        const val PLAY_STORE_PACKAGE = "com.android.vending"

        /**
         * The one place that decides how anything gets installed - clones, apps from sources,
         * files. Root, when switched on and granted, takes every install. Otherwise an install
         * started by hand goes through the system installer, so the user sees its confirmation,
         * and a [background] one uses the method chosen in settings, falling back to the system
         * installer when Shizuku is not usable.
         */
        fun choose(context: Context, background: Boolean): Installer {
            val settings = AppSettings(context)
            return when {
                settings.useRoot && Root.isAvailable() -> RootInstaller
                !background -> StockInstaller
                settings.installMethod == AppSettings.InstallMethod.SHIZUKU &&
                    ShizukuBridge.state() == ShizukuBridge.State.READY -> ShizukuInstaller
                else -> StockInstaller
            }
        }
    }
}

/** The system's session installer. Silent only under the platform's own conditions. */
object StockInstaller : Installer {

    /**
     * A clone is re-signed, and with Play Protect scanning on such an app tends to be blocked -
     * with a full-screen dialog, which a background update must never cause.
     */
    override fun canInstallSilently(context: Context, app: ApkSource): Boolean =
        PlayProtect.isEnabled(context) != true && isUpdateWithoutPrompt(context, app.packageName, app.appInfo.targetSdkVersion)

    /**
     * Whether the system installs an update of [packageName] without asking: it does so only
     * for the installer of record, and only for apps that target a recent enough SDK. Apps
     * that keep their publisher's signature are not held back by Play Protect.
     */
    fun isUpdateWithoutPrompt(context: Context, packageName: String, targetSdk: Int): Boolean {
        val installer = runCatching {
            context.packageManager.getInstallSourceInfo(packageName).installingPackageName
        }.getOrNull()
        return installer == context.packageName && targetSdk >= Build.VERSION.SDK_INT - MAX_TARGET_SDK_LAG
    }

    override suspend fun install(
        context: Context, apks: List<File>, background: Boolean, label: String, installerPackage: String?,
    ): InstallOutcome =
        try {
            withContext(Dispatchers.IO) { ApkInstaller.install(context, apks, background, label) }
            InstallOutcome.Pending
        } catch (e: Exception) {
            InstallOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }

    private const val MAX_TARGET_SDK_LAG = 3
}

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
