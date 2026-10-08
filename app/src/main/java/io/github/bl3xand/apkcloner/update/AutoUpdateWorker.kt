package io.github.bl3xand.apkcloner.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.bl3xand.apkcloner.clone.ApkCloner
import io.github.bl3xand.apkcloner.data.AppRepository
import io.github.bl3xand.apkcloner.install.ApkInstaller
import io.github.bl3xand.apkcloner.settings.AppSettings
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Brings every outdated clone up to the version of the app it was cloned from. */
class AutoUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        if (!context.packageManager.canRequestPackageInstalls()) return@withContext Result.success()

        val cloner = ApkCloner(context)
        val output = File(context.cacheDir, "auto-update")
        try {
            for (clone in AppRepository(context, cloner).installed().clones) {
                if (!clone.updateAvailable) continue
                val request = clone.updateRequest() ?: continue
                UpdateNotifications.showProgress(context, clone.app.label)
                // One broken app must not keep the rest from updating.
                runCatching {
                    val apks = cloner.clone(request, output) { _, _, _ -> }
                    ApkInstaller.install(context, apks, background = true, label = clone.app.label)
                }.onFailure { UpdateNotifications.showAvailable(context, clone.app.label) }
            }
        } finally {
            UpdateNotifications.cancelProgress(context)
            output.deleteRecursively()
        }
        Result.success()
    }

    companion object {
        private const val WORK_NAME = "auto-update"

        /**
         * Applies the current settings. WorkManager persists the schedule itself, so it keeps
         * running across reboots without the app having to be opened again.
         */
        fun schedule(context: Context) {
            val settings = AppSettings(context)
            val workManager = WorkManager.getInstance(context)
            if (!settings.autoUpdate) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoUpdateWorker>(settings.checkIntervalDays, TimeUnit.DAYS).build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
