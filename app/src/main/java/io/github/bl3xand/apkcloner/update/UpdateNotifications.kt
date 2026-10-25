package io.github.bl3xand.apkcloner.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.testing.TestHooks
import io.github.bl3xand.apkcloner.ui.main.MainActivity
import io.github.bl3xand.apkcloner.ui.main.MainTabs
import kotlin.math.abs

/** Everything the background update shows. Dropped by the system if notifications are not allowed. */
object UpdateNotifications {

    private const val CHANNEL = "updates"
    private const val ID_PROGRESS = 1
    private const val ID_AVAILABLE = 2
    private const val PROGRESS_BASE = 1_000_000_000
    private const val PROGRESS_RANGE = 900_000_000

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    /** What every posting of an update notification goes through: the system, and the test
     *  hooks, which stay empty unless the instrumentation enabled them. */
    private fun post(context: Context, id: Int, notification: Notification, title: String) {
        TestHooks.notificationPosted(CHANNEL, id, title)
        manager(context).notify(id, notification)
    }

    /** A stable notification id per package, the pattern SourcesNotifications.idForKey uses. */
    fun progressIdFor(packageName: String): Int = PROGRESS_BASE + abs(packageName.hashCode() % PROGRESS_RANGE)

    /** Ongoing, with an indeterminate bar, for as long as a clone is being rebuilt. */
    fun showProgress(context: Context, label: String, packageName: String) {
        val notification = builder(context)
            .setContentTitle(context.getString(R.string.notification_updating, label))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        post(context, progressIdFor(packageName), notification, context.getString(R.string.notification_updating, label))
    }

    fun cancelProgress(context: Context, packageName: String) = manager(context).cancel(progressIdFor(packageName))

    /** Everything an update pass had shown as its progress, once the pass is over. */
    fun cancelAllProgress(context: Context) = manager(context).cancel(ID_PROGRESS)

    /** Drops a leftover "available"/"failed" notice once it may no longer be true. */
    fun cancelOutdated(context: Context) = manager(context).cancel(ID_AVAILABLE)

    /** The answer to "check now" when nothing, clone or tracked app, has an update. */
    fun showUpToDate(context: Context) {
        val notification = builder(context)
            .setContentTitle(context.getString(R.string.notification_up_to_date))
            .setAutoCancel(true)
            .build()
        post(context, ID_AVAILABLE, notification, context.getString(R.string.notification_up_to_date))
    }

    /** Notify-only mode: outdated clones were found and nothing was installed, by design. */
    fun showAvailable(context: Context, labels: String) =
        showOutdated(context, R.string.notification_available_title, labels)

    /**
     * Automatic mode, and it did not work: the system wanted a confirmation, Play Protect stepped
     * in, or the build failed. The update is still there to be installed by hand. [reason] is
     * the system's own words when it has any; without one, the update simply did not land.
     */
    fun showFailed(context: Context, labels: String, reason: String? = null) =
        showOutdated(context, R.string.notification_failed_title, labels, reason)

    private fun showOutdated(context: Context, title: Int, labels: String, reason: String? = null) {
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, MainTabs.CLONES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = builder(context)
            .setContentTitle(context.getString(title))
            .setContentText(
                if (reason.isNullOrBlank()) context.getString(R.string.notification_available_text, labels)
                else context.getString(R.string.notification_failed_text, labels, reason)
            )
            .setContentIntent(
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            .setAutoCancel(true)
            .build()
        post(context, ID_AVAILABLE, notification, context.getString(title))
    }

    private fun builder(context: Context): NotificationCompat.Builder {
        manager(context).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
        )
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
    }
}
