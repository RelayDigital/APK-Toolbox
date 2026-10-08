package io.github.bl3xand.apkcloner.install

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.github.bl3xand.apkcloner.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val background = intent.getBooleanExtra(EXTRA_BACKGROUND, false)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (background) {
                    // Activities cannot be started from the background, so ask via a notification.
                    notifyConfirmation(context, confirm, intent.getStringExtra(EXTRA_LABEL).orEmpty())
                } else {
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                if (!background) Toast.makeText(context, R.string.install_success, Toast.LENGTH_SHORT).show()
                finished.tryEmit(Unit)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> finished.tryEmit(Unit)
            else -> {
                if (!background) {
                    val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                    Toast.makeText(context, context.getString(R.string.install_failed, reason), Toast.LENGTH_LONG).show()
                }
                finished.tryEmit(Unit)
            }
        }
    }

    private fun notifyConfirmation(context: Context, confirm: Intent, label: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_update_title, label))
            .setContentText(context.getString(R.string.notification_update_text))
            .setContentIntent(
                PendingIntent.getActivity(context, label.hashCode(), confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            .setAutoCancel(true)
            .build()
        // Silently dropped by the system if the notification permission was not granted.
        manager.notify(label.hashCode(), notification)
    }

    companion object {
        const val EXTRA_BACKGROUND = "background"
        const val EXTRA_LABEL = "label"
        private const val CHANNEL = "updates"

        private val finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Fires whenever an install session ends, successfully or not. */
        val sessionFinished = finished.asSharedFlow()
    }
}
