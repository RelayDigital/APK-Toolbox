package io.github.bl3xand.apkcloner.sources.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.formatDownloadSize
import io.github.bl3xand.apkcloner.sources.install.SourcesInstaller
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.ui.MainActivity
import io.github.bl3xand.apkcloner.ui.MainTabs
import kotlin.math.abs

/** Everything the Sources tab reports through notifications. */
object SourcesNotifications {
    // Kept apart from the ids of the clone notifications.
    private const val BASE = 1000
    const val ID_COMPLETE_INSTALL = BASE + 1
    const val ID_UPDATES = BASE + 2
    const val ID_SILENT_UPDATE = BASE + 3
    const val ID_CHECKING = BASE + 4
    const val ID_CHECK_ERROR = BASE + 5
    const val ID_APPS_REMOVED = BASE + 6
    const val ID_TRACK_ONLY_UPDATES = BASE + 7
    const val ID_POSSIBLY_UPDATED = BASE + 8
    private const val DOWNLOAD_BASE = 100_000
    private const val DOWNLOAD_RANGE = 1_000_000_000

    const val EXTRA_APP_ID = "sourcesAppId"
    const val EXTRA_MESSAGE = "sourcesMessage"

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun builder(
        context: Context,
        channel: String,
        channelName: String,
        importance: Int,
    ): NotificationCompat.Builder {
        manager(context).createNotificationChannel(NotificationChannel("sources_$channel", channelName, importance))
        return NotificationCompat.Builder(context, "sources_$channel").setSmallIcon(R.drawable.ic_notification)
    }

    private fun openIntent(context: Context, requestCode: Int, appId: String? = null, message: String? = null) =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainTabs.SOURCES)
                .putExtra(EXTRA_APP_ID, appId)
                .putExtra(EXTRA_MESSAGE, message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun cancel(context: Context, id: Int) = manager(context).cancel(id)

    fun idForKey(key: String): Int = DOWNLOAD_BASE + abs(key.hashCode() % DOWNLOAD_RANGE)

    private fun updateMessage(
        updates: List<TrackedApp>,
        singleKey: String,
        pluralKey: String,
        includeVersion: Boolean = false,
    ): String {
        if (updates.isEmpty()) return ""
        val first = updates[0]
        if (updates.size == 1) {
            return if (includeVersion) Tr.get(singleKey, first.finalName, first.latestVersion) else Tr.get(singleKey, first.finalName)
        }
        return Tr.plural(pluralKey, updates.size - 1, first.finalName, (updates.size - 1).toString())
    }

    private fun show(
        context: Context,
        id: Int,
        channel: String,
        channelName: String,
        importance: Int,
        title: String,
        text: String,
        appId: String? = null,
        dialogMessage: String? = null,
    ) {
        val notification = builder(context, channel, channelName, importance)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent(context, id, appId, dialogMessage))
            .setAutoCancel(true)
            .build()
        manager(context).notify(id, notification)
    }

    fun updatesAvailable(context: Context, updates: List<TrackedApp>, trackOnly: Boolean = false) = show(
        context, if (trackOnly) ID_TRACK_ONLY_UPDATES else ID_UPDATES, "UPDATES_AVAILABLE",
        Tr.get("updatesAvailableNotifChannel"), NotificationManager.IMPORTANCE_HIGH,
        Tr.get(if (trackOnly) "trackOnlyUpdatesAvailable" else "updatesAvailable"),
        updateMessage(updates, "xHasAnUpdate", "xAndNMoreUpdatesAvailable"),
        appId = updates.singleOrNull()?.id,
    )

    fun silentUpdate(context: Context, app: TrackedApp, succeeded: Boolean) = show(
        context, ID_SILENT_UPDATE + abs(app.id.hashCode() % 100_000) * 10, "APPS_UPDATED", Tr.get("appsUpdatedNotifChannel"),
        NotificationManager.IMPORTANCE_DEFAULT,
        Tr.get(if (succeeded) "appsUpdated" else "appsNotUpdated"),
        updateMessage(
            listOf(app), if (succeeded) "xWasUpdatedToY" else "xWasNotUpdatedToY",
            if (succeeded) "xAndNMoreUpdatesInstalled" else "xAndNMoreUpdatesFailed", includeVersion = true,
        ),
        appId = app.id,
    )

    /** The system installer was started in the background but its result could not be seen. */
    fun possiblyUpdated(context: Context, app: TrackedApp) = show(
        context, ID_POSSIBLY_UPDATED + abs(app.id.hashCode() % 100_000) * 10, "APPS_POSSIBLY_UPDATED",
        Tr.get("appsPossiblyUpdatedNotifChannel"), NotificationManager.IMPORTANCE_DEFAULT,
        Tr.get("appsPossiblyUpdated"),
        updateMessage(listOf(app), "xWasPossiblyUpdatedToY", "xAndNMoreUpdatesPossiblyInstalled", includeVersion = true),
        appId = app.id,
    )

    fun checkError(context: Context, error: String, idOffset: Int = 0) = show(
        context, ID_CHECK_ERROR + idOffset, "BG_UPDATE_CHECK_ERROR", Tr.get("errorCheckingUpdatesNotifChannel"),
        NotificationManager.IMPORTANCE_HIGH, Tr.get("errorCheckingUpdates"), error,
        dialogMessage = "${Tr.get("errorCheckingUpdates")}\n$error",
    )

    fun appsRemoved(context: Context, namedReasons: List<Pair<String, String>>) = show(
        context, ID_APPS_REMOVED, "APPS_REMOVED", Tr.get("appsRemovedNotifChannel"), NotificationManager.IMPORTANCE_HIGH,
        Tr.get("appsRemoved"),
        namedReasons.joinToString("\n") { Tr.get("xWasRemovedDueToErrorY", it.first, it.second) },
    )

    fun completeInstallation(context: Context) = show(
        context, ID_COMPLETE_INSTALL, "COMPLETE_INSTALL", Tr.get("completeAppInstallationNotifChannel"),
        NotificationManager.IMPORTANCE_HIGH, Tr.get("completeAppInstallation"), Tr.get("obtainiumMustBeOpenToInstallApps"),
    )

    fun checking(context: Context, count: Int) {
        val notification = builder(
            context, "BG_UPDATE_CHECK", Tr.get("checkingForUpdatesNotifChannel"), NotificationManager.IMPORTANCE_MIN,
        ).setContentTitle(Tr.get("checkingForUpdates")).setContentText(Tr.plural("apps", count))
            .setProgress(0, 0, true).setOngoing(true).build()
        manager(context).notify(ID_CHECKING, notification)
    }

    /** [percent] below zero shows an indeterminate bar. [appId] adds a Cancel action. */
    fun download(
        context: Context,
        key: String,
        name: String,
        percent: Int,
        received: Long? = null,
        total: Long? = null,
        appId: String? = null,
    ) {
        val id = idForKey(key)
        val builder = builder(
            context, "APP_DOWNLOADING", Tr.get("downloadingXNotifChannel", Tr.get("app")), NotificationManager.IMPORTANCE_LOW,
        ).setContentTitle(Tr.get("downloadingX", name))
            .setContentText(formatDownloadSize(received, total) ?: "")
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (appId != null) {
            builder.addAction(
                0, Tr.get("cancel"),
                PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, CancelDownloadReceiver::class.java).putExtra(EXTRA_APP_ID, appId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        manager(context).notify(id, builder.build())
    }

    fun downloaded(context: Context, fileName: String, url: String) = show(
        context, idForKey("done|$url"), "FILE_DOWNLOADED", Tr.get("downloadedXNotifChannel", Tr.get("app")),
        NotificationManager.IMPORTANCE_DEFAULT, Tr.get("downloadedX", fileName), "",
    )
}
