package io.github.bl3xand.apkcloner.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File

object ApkInstaller {

    /** Hands the APKs (base plus splits) to the system installer as a single session. */
    /**
     * [background] marks sessions started without the app on screen: if the system insists on a
     * confirmation, it is offered as a notification instead of being launched directly.
     */
    fun install(context: Context, apks: List<File>, background: Boolean = false, label: String = "") {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Lets the system skip the confirmation when updating a clone this app installed itself.
        // Anything else still gets the usual prompt.
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            for (apk in apks) {
                session.openWrite(apk.name, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                    session.fsync(out)
                }
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallReceiver::class.java)
                    .putExtra(InstallReceiver.EXTRA_BACKGROUND, background)
                    .putExtra(InstallReceiver.EXTRA_LABEL, label),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }
}
