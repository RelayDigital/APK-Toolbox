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
import java.util.Collections
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first

class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (intent.getBooleanExtra(EXTRA_BACKGROUND, false)) {
            onBackgroundResult(context, intent, status)
            return
        }
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "?"
        // Results belong to their own session: concurrent installs each answer their own
        // waiter, and a session whose caller reports the outcome itself is left to it.
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The ask is not the outcome; a standing claim must survive it, or the
                // result that follows would be announced by the receiver after all.
                AppLog.debug("System installer: asking the user to confirm $packageName")
                val confirm = intent.parcelable<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.info("System installer: installed $packageName")
                if (!unclaimSelfReport(sessionId)) Messages.show(context, context.getString(R.string.install_success))
                finish(SessionResult(sessionId, packageName, status))
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                AppLog.info("System installer: cancelled by the user ($packageName)")
                unclaimSelfReport(sessionId)
                finish(SessionResult(sessionId, packageName, status))
            }
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                AppLog.error("System installer: failed for $packageName, status $status: $reason")
                if (!unclaimSelfReport(sessionId)) Messages.show(context, context.getString(R.string.install_failed, reason))
                finish(SessionResult(sessionId, packageName, status, reason))
            }
        }
    }

    /**
     * A background update either goes through silently or not at all: anything that would need
     * the user is dropped and reported as a failed update instead.
     */
    private fun onBackgroundResult(context: Context, intent: Intent, status: Int) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            // The ask cannot be shown and the session is abandoned: that is its outcome.
            finish(SessionResult(sessionId, intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME), status))
            return
        }
        // A session whose caller waits for it (InstallCoordinator users) reports the
        // outcome itself, with the installer's own words; only an unwitnessed background
        // install is reported here.
        if (status != PackageInstaller.STATUS_SUCCESS && !unclaimSelfReport(sessionId)) {
            UpdateNotifications.showFailed(context, intent.getStringExtra(EXTRA_LABEL).orEmpty())
        }
        finish(SessionResult(sessionId, intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME), status))
    }

    private fun finish(result: SessionResult) {
        finished.tryEmit(result)
    }

    /** How the end of one install session looks to whoever waits for it. */
    class SessionResult(val sessionId: Int, val packageName: String?, val status: Int, val reason: String? = null) {
        val succeeded: Boolean get() = status == PackageInstaller.STATUS_SUCCESS

        /** The system's own words about what went wrong, or at least its status number. */
        val reasonText: String get() = reason.orEmpty().ifBlank { "status $status" }
    }

    companion object {
        const val EXTRA_BACKGROUND = "background"
        const val EXTRA_LABEL = "label"

        /** Stands in for the sessionId when an installer has no session to name. */
        const val NO_SESSION = -1

        // The last results are replayed, so a waiter that starts only after handing the
        // session to the system still sees how it ended.
        private val finished = MutableSharedFlow<SessionResult>(extraBufferCapacity = 16, replay = 32)

        /** Fires whenever an install ends, successfully or not, carrying whose it was. */
        val sessionFinished = finished.asSharedFlow()

        private val selfReporting = Collections.synchronizedSet(HashSet<Int>())

        /**
         * Says the caller of [sessionId] reports the outcome itself, so the receiver stays
         * silent about it. Must be called before the session is committed; the claim is
         * consumed by the result that belongs to it.
         */
        fun claimSelfReport(sessionId: Int) {
            selfReporting.add(sessionId)
        }

        /** Whether [sessionId] has a self-reporting claim standing (tests). */
        internal fun isSelfReported(sessionId: Int): Boolean = sessionId in selfReporting

        private fun unclaimSelfReport(sessionId: Int): Boolean = selfReporting.remove(sessionId)

        /** Waits for how [sessionId] ended; a recent end is replayed to a late waiter. */
        suspend fun awaitSession(sessionId: Int): SessionResult = sessionFinished.first { it.sessionId == sessionId }

        /**
         * For installers that learn the result themselves instead of through this receiver.
         * Names the package when it is known; waiters for a particular session are not woken.
         */
        fun notifyFinished(packageName: String? = null) {
            finished.tryEmit(SessionResult(NO_SESSION, packageName, PackageInstaller.STATUS_SUCCESS))
        }

        /** An install session's end, as the system's broadcast would report it (tests). */
        internal fun reportForTest(sessionId: Int, packageName: String?, status: Int, reason: String? = null) {
            finished.tryEmit(SessionResult(sessionId, packageName, status, reason))
        }
    }
}