package io.github.bl3xand.apkcloner.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.bl3xand.apkcloner.clone.ApkCloner
import io.github.bl3xand.apkcloner.data.AppRepository
import io.github.bl3xand.apkcloner.install.InstallOutcome
import io.github.bl3xand.apkcloner.install.Installer
import io.github.bl3xand.apkcloner.settings.AppSettings
import io.github.bl3xand.apkcloner.shizuku.ShizukuBridge
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Looks for clones older than the app they were made from and, depending on the settings,
 * either updates them or just tells the user.
 */
class AutoUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val settings = AppSettings(context)
        val autoInstall = settings.autoInstall
        if (autoInstall && settings.installMethod == AppSettings.InstallMethod.SHIZUKU &&
            ShizukuBridge.stateAfterStartup() != ShizukuBridge.State.READY && runAttemptCount < SHIZUKU_RETRIES &&
            !inputData.getBoolean(KEY_MANUAL, false)
        ) {
            // Typically right after a reboot, when this check is already due but Shizuku has not
            // been started yet. Come back later instead of settling for the standard installer.
            return@withContext Result.retry()
        }
        val cloner = ApkCloner(context)
        val installer = Installer.forBackground(context)
        val output = File(context.cacheDir, "auto-update")
        // Whatever the previous run reported is about to be re-evaluated.
        UpdateNotifications.cancelOutdated(context)
        val available = ArrayList<String>()
        val failed = ArrayList<String>()
        var updated = 0
        try {
            for (clone in AppRepository(context, cloner).installed().clones) {
                if (!clone.updateAvailable) continue
                val request = clone.updateRequest() ?: continue
                if (!autoInstall) {
                    available += clone.app.label
                    continue
                }
                // Decided up front, so a clone the system would only install after a
                // confirmation is not rebuilt for nothing.
                if (!installer.canInstallSilently(context, clone.app)) {
                    failed += clone.app.label
                    continue
                }
                UpdateNotifications.showProgress(context, clone.app.label)
                // One broken app must not keep the rest from updating.
                val outcome = try {
                    val apks = cloner.clone(request, output) { _, _, _ -> }
                    installer.install(context, apks, background = true, label = clone.app.label)
                } catch (e: Exception) {
                    InstallOutcome.Failed(e.message.orEmpty())
                }
                if (outcome is InstallOutcome.Failed) failed += clone.app.label else updated++
            }
        } finally {
            UpdateNotifications.cancelProgress(context)
            output.deleteRecursively()
        }
        // A successful automatic update says nothing beyond the progress it showed.
        if (inputData.getBoolean(KEY_MANUAL, false) && available.isEmpty() && failed.isEmpty() && updated == 0) {
            // Asked for by hand: say so even when there was nothing to do.
            UpdateNotifications.showUpToDate(context)
        }
        if (available.isNotEmpty()) UpdateNotifications.showAvailable(context, available.joinToString())
        if (failed.isNotEmpty()) UpdateNotifications.showFailed(context, failed.joinToString())
        Result.success()
    }

    companion object {
        private const val WORK_NAME = "auto-update"
        private const val WORK_NAME_MANUAL = "auto-update-now"
        private const val KEY_MANUAL = "manual"

        // Linear backoff: retries about 10, 30 and 60 minutes after the first attempt.
        private const val SHIZUKU_RETRIES = 3
        private const val SHIZUKU_RETRY_STEP_MINUTES = 10L

        /** One check right now, whatever the schedule says. The result comes as a notification. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Applies the current settings. WorkManager persists the schedule itself, so it keeps
         * running across reboots without the app having to be opened again.
         */
        fun schedule(context: Context) {
            val settings = AppSettings(context)
            val workManager = WorkManager.getInstance(context)
            if (!settings.checkUpdates) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoUpdateWorker>(settings.checkIntervalDays, TimeUnit.DAYS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, SHIZUKU_RETRY_STEP_MINUTES, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
