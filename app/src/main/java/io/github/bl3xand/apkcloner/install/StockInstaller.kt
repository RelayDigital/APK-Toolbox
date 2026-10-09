package io.github.bl3xand.apkcloner.install

import android.content.Context
import android.os.Build
import io.github.bl3xand.apkcloner.data.ApkSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
