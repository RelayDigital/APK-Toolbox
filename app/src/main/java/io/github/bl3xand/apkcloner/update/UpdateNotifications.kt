package io.github.bl3xand.apkcloner.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.ui.MainActivity
import io.github.bl3xand.apkcloner.ui.MainTabs

/** Everything the background update shows. Dropped by the system if notifications are not allowed. */
object UpdateNotifications {

    private const val CHANNEL = "updates"
    private const val ID_PROGRESS = 1
    private const val ID_AVAILABLE = 2

    /** Ongoing, with an indeterminate bar, for as long as a clone is being rebuilt. */
    fun showProgress(context: Context, label: String) {
        val notification = builder(context)
            .setContentTitle(context.getString(R.string.notification_updating, label))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        manager(context).notify(ID_PROGRESS, notification)
    }

    fun cancelProgress(context: Context) = manager(context).cancel(ID_PROGRESS)

    /** Drops a leftover "available"/"failed" notice once it may no longer be true. */
    fun cancelOutdated(context: Context) = manager(context).cancel(ID_AVAILABLE)

    /** The answer to "check now" when nothing, clone or tracked app, has an update. */
    fun showUpToDate(context: Context) {
        val notification = builder(context)
            .setContentTitle(context.getString(R.string.notification_up_to_date))
            .setAutoCancel(true)
            .build()
        manager(context).notify(ID_AVAILABLE, notification)
    }

    /** Notify-only mode: outdated clones were found and nothing was installed, by design. */
    fun showAvailable(context: Context, labels: String) =
        showOutdated(context, R.string.notification_available_title, labels)

    /**
     * Automatic mode, and it did not work: the system wanted a confirmation, Play Protect stepped
     * in, or the build failed. The update is still there to be installed by hand.
     */
    fun showFailed(context: Context, labels: String) =
        showOutdated(context, R.string.notification_failed_title, labels)

    private fun showOutdated(context: Context, title: Int, labels: String) {
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, MainTabs.CLONES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = builder(context)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(R.string.notification_available_text, labels))
            .setContentIntent(
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            .setAutoCancel(true)
            .build()
        manager(context).notify(ID_AVAILABLE, notification)
    }

    private fun builder(context: Context): NotificationCompat.Builder {
        manager(context).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
        )
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
    }

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}
