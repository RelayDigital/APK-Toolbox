package io.github.bl3xand.apkcloner.sources.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.bl3xand.apkcloner.sources.install.SourcesInstaller

/** The Cancel action of a download notification. */
class CancelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(SourcesNotifications.EXTRA_APP_ID)?.let { SourcesInstaller.get(context).cancelDownload(it) }
    }
}
