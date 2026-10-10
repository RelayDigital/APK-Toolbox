package io.github.bl3xand.apkcloner.install

import io.github.bl3xand.apkcloner.compat.parcelable
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.ui.Messages
import io.github.bl3xand.apkcloner.update.UpdateNotifications
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (intent.getBooleanExtra(EXTRA_BACKGROUND, false)) {
            onBackgroundResult(context, intent, status)
            return
        }
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "?"
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                AppLog.debug("System installer: asking the user to confirm $packageName")
                val confirm = intent.parcelable<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.info("System installer: installed $packageName")
                if (!reportedByCaller) Messages.show(context, context.getString(R.string.install_success))
                finished.tryEmit(Unit)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                AppLog.info("System installer: cancelled by the user ($packageName)")
                finished.tryEmit(Unit)
            }
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                AppLog.error("System installer: failed for $packageName, status $status: $reason")
                Messages.show(context, context.getString(R.string.install_failed, reason))
                finished.tryEmit(Unit)
            }
        }
    }

    /**
     * A background update either goes through silently or not at all: anything that would need
     * the user is dropped and reported as a failed update instead.
     */
    private fun onBackgroundResult(context: Context, intent: Intent, status: Int) {
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            UpdateNotifications.showFailed(context, intent.getStringExtra(EXTRA_LABEL).orEmpty())
        }
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) finished.tryEmit(Unit)
    }

    companion object {
        /**
         * Set while a caller that reports the outcome itself waits for the installer, so that a
         * successful install is announced once and not twice.
         */
        @Volatile
        var reportedByCaller = false

        const val EXTRA_BACKGROUND = "background"
        const val EXTRA_LABEL = "label"

        private val finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Fires whenever an install ends, successfully or not. */
        val sessionFinished = finished.asSharedFlow()

        /** For installers that learn the result themselves instead of through this receiver. */
        fun notifyFinished() {
            finished.tryEmit(Unit)
        }
    }
}
