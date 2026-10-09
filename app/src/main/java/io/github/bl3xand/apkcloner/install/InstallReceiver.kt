package io.github.bl3xand.apkcloner.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import io.github.bl3xand.apkcloner.R
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
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Messages.show(context, context.getString(R.string.install_success))
                finished.tryEmit(Unit)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> finished.tryEmit(Unit)
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
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
