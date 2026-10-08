package io.github.bl3xand.apkclonner.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import io.github.bl3xand.apkclonner.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, R.string.install_success, Toast.LENGTH_SHORT).show()
                finished.tryEmit(Unit)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> finished.tryEmit(Unit)
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, context.getString(R.string.install_failed, reason), Toast.LENGTH_LONG).show()
                finished.tryEmit(Unit)
            }
        }
    }

    companion object {
        private val finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Fires whenever an install session ends, successfully or not. */
        val sessionFinished = finished.asSharedFlow()
    }
}
