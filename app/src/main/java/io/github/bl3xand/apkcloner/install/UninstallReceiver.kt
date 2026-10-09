package io.github.bl3xand.apkcloner.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import io.github.bl3xand.apkcloner.log.AppLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Delivers the outcome of a [PackageInstaller.uninstall]. The system first asks for confirmation
 * (STATUS_PENDING_USER_ACTION); the real result arrives afterwards, keyed by package name so a
 * caller can wait for its own uninstall.
 */
class UninstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.info("System uninstaller: removed $packageName")
                finished.tryEmit(packageName to true)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                AppLog.info("System uninstaller: cancelled by the user ($packageName)")
                finished.tryEmit(packageName to false)
            }
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                AppLog.error("System uninstaller: failed for $packageName, status $status: $reason")
                finished.tryEmit(packageName to false)
            }
        }
    }

    companion object {
        private val finished = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 8)

        /** (package name, removed?) for each finished uninstall. */
        val results = finished.asSharedFlow()
    }
}
