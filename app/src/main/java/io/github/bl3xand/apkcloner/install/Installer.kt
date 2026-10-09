package io.github.bl3xand.apkcloner.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.settings.AppSettings
import io.github.bl3xand.apkcloner.settings.InstallMethod
import io.github.bl3xand.apkcloner.shizuku.ShizukuBridge
import io.github.bl3xand.apkcloner.shizuku.ShizukuState
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

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
                settings.installMethod == InstallMethod.SHIZUKU &&
                    ShizukuBridge.state() == ShizukuState.READY -> ShizukuInstaller
                else -> StockInstaller
            }
        }

        /**
         * Shows the system uninstall prompt for [packageName] and suspends until it finishes,
         * returning true only if the app was actually removed. Callers can show progress for the
         * whole wait and tell a real removal from the user backing out of the prompt.
         */
        suspend fun uninstall(context: Context, packageName: String): Boolean {
            val pending = PendingIntent.getBroadcast(
                context, packageName.hashCode(),
                Intent(context, UninstallReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            return coroutineScope {
                // Subscribed before the prompt starts, so a quick result cannot slip past us.
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    UninstallReceiver.results.first { it.first == packageName }.second
                }
                try {
                    context.packageManager.packageInstaller.uninstall(packageName, pending.intentSender)
                } catch (e: Exception) {
                    result.cancel()
                    AppLog.error("Uninstall of $packageName could not start: ${e.message}")
                    return@coroutineScope false
                }
                result.await()
            }
        }
    }
}
