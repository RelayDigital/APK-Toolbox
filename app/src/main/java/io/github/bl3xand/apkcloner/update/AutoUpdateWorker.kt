package io.github.bl3xand.apkcloner.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.bl3xand.apkcloner.clone.ApkCloner
import io.github.bl3xand.apkcloner.data.AppRepository
import io.github.bl3xand.apkcloner.install.InstallOutcome
import io.github.bl3xand.apkcloner.install.Installer
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.settings.AppSettings
import io.github.bl3xand.apkcloner.settings.InstallMethod
import io.github.bl3xand.apkcloner.shizuku.ShizukuBridge
import io.github.bl3xand.apkcloner.shizuku.ShizukuState
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import io.github.bl3xand.apkcloner.sources.work.SourcesBackground
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
        if (autoInstall && settings.installMethod == InstallMethod.SHIZUKU &&
            ShizukuBridge.stateAfterStartup() != ShizukuState.READY && runAttemptCount < SHIZUKU_RETRIES &&
            !inputData.getBoolean(KEY_MANUAL, false)
        ) {
            // Typically right after a reboot, when this check is already due but Shizuku has not
            // been started yet. Come back later instead of settling for the standard installer.
            return@withContext Result.retry()
        }
        AppLog.init(context)
        val cloner = ApkCloner(context)
        val installer = Installer.choose(context, background = true)
        val output = File(context.cacheDir, "auto-update")
        // Whatever the previous run reported is about to be re-evaluated.
        UpdateNotifications.cancelOutdated(context)
        val available = ArrayList<String>()
        val failed = ArrayList<String>()
        var updated = 0
        try {
            // Without the network or the charger the settings ask for, updates are only reported.
            val mayInstall = autoInstall && restrictionsMet(context, settings)
            val clones = if (settings.checkClones) AppRepository(context, cloner).installed().clones else emptyList()
            for (clone in clones) {
                if (!clone.wantsUpdate) continue
                val request = clone.updateRequest() ?: continue
                if (!mayInstall) {
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
                if (outcome is InstallOutcome.Failed) {
                    AppLog.error("Background update of clone ${clone.app.packageName} failed: ${outcome.reason}")
                    failed += clone.app.label
                } else {
                    AppLog.info("Clone ${clone.app.packageName} updated in the background")
                    updated++
                }
            }
        } finally {
            UpdateNotifications.cancelProgress(context)
            output.deleteRecursively()
        }
        if (available.isNotEmpty()) UpdateNotifications.showAvailable(context, available.joinToString())
        if (failed.isNotEmpty()) UpdateNotifications.showFailed(context, failed.joinToString())
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        // Apps tracked from sources share this schedule when their own switch is on.
        var sourcesHadSomething = false
        // A clone that a source keeps current is a clone all the same: with the sources' switch
        // off it is still looked after under the clones' one.
        val trackedClones = if (settings.checkClones && !settings.checkSources) {
            SourcesRepository.get(context).installedAsClones().values.map { it.app.id }.toSet()
        } else emptySet()
        if (settings.checkSources || trackedClones.isNotEmpty()) {
            try {
                sourcesHadSomething = SourcesBackground.run(context, forceAll = manual, only = trackedClones.takeIf { !settings.checkSources })
            } catch (e: Exception) {
                AppLog.error("Background check of sources failed", e)
                sourcesHadSomething = true
            }
        }
        // A successful automatic update says nothing beyond the progress it showed. Asked for
        // by hand, the check answers even when there was nothing to do anywhere.
        if (manual && available.isEmpty() && failed.isEmpty() && updated == 0 && !sourcesHadSomething) {
            UpdateNotifications.showUpToDate(context)
        }
        if (autoInstall && !restrictionsMet(context, settings)) {
            // Found, but not allowed to install yet: come back by itself once the network and
            // the charger the settings ask for are there.
            val waiting = available.isNotEmpty() || (
                settings.checkSources &&
                    SourcesRepository.get(context).findAppIdsWithPendingUpdates(installedOnly = true).isNotEmpty()
                )
            if (waiting) installWhenAllowed(context, settings)
        }
        Result.success()
    }

    companion object {
        /** Whether the "Wi-Fi only" and "while charging" conditions hold right now. */
        fun restrictionsMet(context: Context, settings: AppSettings): Boolean {
            if (settings.wifiOnly) {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
                val unmetered = capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
                if (!unmetered) return false
            }
            if (settings.chargingOnly &&
                !context.getSystemService(BatteryManager::class.java).isCharging
            ) {
                return false
            }
            return true
        }

        private const val WORK_NAME = "auto-update"
        private const val WORK_NAME_MANUAL = "auto-update-now"
        private const val WORK_NAME_WAITING = "auto-update-waiting"
        private const val KEY_MANUAL = "manual"

        // Linear backoff: retries about 10, 30 and 60 minutes after the first attempt.
        private const val SHIZUKU_RETRIES = 3
        private const val SHIZUKU_RETRY_STEP_MINUTES = 10L

        /** Queues one more run for the moment the conditions for installing hold. */
        private fun installWhenAllowed(context: Context, settings: AppSettings) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(settings.chargingOnly)
                .build()
            val request = OneTimeWorkRequestBuilder<AutoUpdateWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME_WAITING, ExistingWorkPolicy.REPLACE, request)
        }

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
                workManager.cancelUniqueWork(WORK_NAME_WAITING)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoUpdateWorker>(settings.checkIntervalDays, TimeUnit.DAYS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, SHIZUKU_RETRY_STEP_MINUTES, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
