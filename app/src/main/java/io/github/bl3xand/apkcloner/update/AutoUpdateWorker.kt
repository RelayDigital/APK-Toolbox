package io.github.bl3xand.apkcloner.update

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import io.github.bl3xand.apkcloner.clone.build.ApkCloner
import io.github.bl3xand.apkcloner.data.AppRepository
import io.github.bl3xand.apkcloner.install.InstallCoordinator
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
        // What the pass over installed clones looked at; the outcome line after it says the rest.
        var clonesChecked = 0
        try {
            // Without the network or the charger the settings ask for, updates are only reported.
            val mayInstall = autoInstall && restrictionsMet(context, settings)
            val clones = if (settings.checkClones) AppRepository(context, cloner).installed().clones else emptyList()
            clonesChecked = clones.size
            // The whole pass installs as one unit, so an obtain asked for on screen waits
            // for it instead of interleaving its prompts (InstallCoordinator).
            InstallCoordinator.backgroundBatch {
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
                    UpdateNotifications.showProgress(context, clone.app.label, clone.app.packageName)
                    // One broken app must not keep the rest from updating.
                    val outcome = try {
                        val apks = cloner.clone(request, output) { _, _, _ -> }
                        InstallCoordinator.installing(clone.app.packageName, background = true) {
                            commit { installer.install(context, apks, background = true, label = clone.app.label) }
                        }
                    } catch (e: Exception) {
                        InstallOutcome.Failed(e.message.orEmpty())
                    } finally {
                        UpdateNotifications.cancelProgress(context, clone.app.packageName)
                    }
                    if (outcome is InstallOutcome.Failed) {
                        AppLog.error("Background update of clone ${clone.app.packageName} failed: ${outcome.reason}")
                        failed += clone.app.label
                    } else {
                        AppLog.info("Clone ${clone.app.packageName} updated in the background")
                        updated++
                    }
                }
            }
        } finally {
            // Old single-id leftovers from versions that had one progress notification.
            UpdateNotifications.cancelAllProgress(context)
            output.deleteRecursively()
        }
        // Behind are the clones whose update is waiting for the conditions the settings ask for;
        // their own notifications say which they are, this line only counts them.
        AppLog.info(
            "Clones checked: $clonesChecked, behind: ${available.size + failed.size}, updated: $updated, failed: ${failed.size}",
        )
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
        val fromWaitingRun = inputData.getBoolean(KEY_WAITING, false)
        if (autoInstall && !restrictionsMet(context, settings)) {
            // Found, but not allowed to install yet: come back by itself once the network and
            // the charger the settings ask for are there.
            val waiting = available.isNotEmpty() || (
                settings.checkSources &&
                    SourcesRepository.get(context).findAppIdsWithPendingUpdates(installedOnly = true).isNotEmpty()
                )
            if (waiting) {
                installWhenAllowed(context, settings, fromWaitingRun)
            } else if (fromWaitingRun) {
                forgetWaitingEnqueuedAt(context)
            }
        } else if (fromWaitingRun) {
            // The waiting run did its work: nothing stands behind it anymore, so the pacing
            // timestamp must not keep the next pass from queueing fresh waiting work.
            forgetWaitingEnqueuedAt(context)
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
        private const val KEY_WAITING = "waiting"

        // Linear backoff: retries about 10, 30 and 60 minutes after the first attempt.
        private const val SHIZUKU_RETRIES = 3
        private const val SHIZUKU_RETRY_STEP_MINUTES = 10L

        /**
         * How long freshly queued waiting work counts as standing: while the charging signals
         * disagree, a scheduled or manual pass leaves work younger than this alone, since
         * replacing it would only restart its pacing delay. Matches [SIGNAL_MISMATCH_REQUEUE_MS],
         * so a pass between two paced re-queues never postpones one.
         */
        private const val WAITING_FRESH_MS = 15 * 60_000L

        /**
         * How long a waiting run that found the charging signals disagreeing waits before it
         * runs again. The JobScheduler charging constraint is satisfied by the charger being
         * plugged in, while restrictionsMet asks the battery whether it is really charging: on a
         * battery with a charge limit (a Pixel held at 80%), plugged in means NOT_CHARGING, and
         * the job would otherwise restart its own replacement as fast as the system lets it -
         * a few times a second, hundreds of runs in minutes (home-bl1). This floor turns that
         * loop into a bounded, lazy re-check.
         */
        internal const val SIGNAL_MISMATCH_REQUEUE_MS = 15 * 60_000L

        /**
         * Whether the charger's plugged state and the battery's own answer disagree, the state
         * on a charge-limited battery: plugged in (what JobScheduler's charging constraint
         * reads), yet not charging (what BatteryManager.isCharging reports, status 4).
         */
        internal fun chargingSignalsDisagree(pluggedStatus: Int, isCharging: Boolean): Boolean =
            pluggedStatus != 0 && !isCharging

        /**
         * Whether a pass that found updates to wait for should queue the waiting work, and
         * after what delay in milliseconds.
         *
         * [isCharging] is the battery's own answer (BatteryManager.isCharging), [pluggedStatus]
         * the plugged state the waiting work's charging constraint is satisfied by (0 = on
         * battery), [lastEnqueuedAt] when the waiting work was last enqueued (epoch millis,
         * 0 when nothing stands), [now] the current time. The decision, not the WorkManager
         * wiring around it; plain-Kotlin tests cover it (WaitingWorkDecisionTest).
         *
         * No delay when the signals agree: the work is held by its constraints alone, and
         * re-queuing it right away is what makes it fire the moment the network or a real
         * charge starts. A delay when they disagree: plugged in but not charging means the
         * charging constraint is already satisfied, so an immediate re-queue would restart
         * the job at whatever pace the system allows - the install storm of home-bl1. While
         * they disagree, fresh standing work is left alone instead of being replaced, and a
         * pass that finds none standing (or work gone stale) queues it back with the delay -
         * which is also how the periodic pass keeps the deferral alive.
         */
        internal fun shouldRequeueWaitingWork(
            isCharging: Boolean,
            pluggedStatus: Int,
            lastEnqueuedAt: Long,
            now: Long,
        ): Pair<Boolean, Long> {
            if (!chargingSignalsDisagree(pluggedStatus, isCharging)) return true to 0L
            if (lastEnqueuedAt == 0L || now - lastEnqueuedAt >= WAITING_FRESH_MS) {
                return true to SIGNAL_MISMATCH_REQUEUE_MS
            }
            return false to SIGNAL_MISMATCH_REQUEUE_MS
        }

        /**
         * Queues one more run for the moment the conditions for installing hold. The work is
         * replaced, not kept: a waiting run must be able to name its own successor, and a
         * scheduled or manual pass must be able to put a properly paced request where an
         * undelayed one stands. What keeps the replacement from running hot is the decision
         * above: while the charging signals disagree, a run either waits out the pacing delay
         * or leaves fresh standing work alone.
         */
        private fun installWhenAllowed(context: Context, settings: AppSettings, fromWaitingRun: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_WAITING, Context.MODE_PRIVATE)
            // The charger's plugged state only matters to work that carries the charging
            // constraint; without it there is nothing the battery's answer could disagree with.
            val signals = if (settings.chargingOnly) {
                val battery = context.getSystemService(BatteryManager::class.java)
                battery.isCharging to pluggedStatus(context)
            } else {
                true to 0
            }
            // A waiting run is the standing work itself, and it is ending: nothing stands
            // behind it that a re-queue could cancel, so the freshness window does not apply.
            val standingSince = if (fromWaitingRun) 0L else prefs.getLong(KEY_LAST_ENQUEUED_AT, 0L)
            val (enqueue, delayMs) = shouldRequeueWaitingWork(
                isCharging = signals.first,
                pluggedStatus = signals.second,
                lastEnqueuedAt = standingSince,
                now = System.currentTimeMillis(),
            )
            if (!enqueue) {
                AppLog.info("Waiting work is fresh; left standing while plugged in but not charging")
                return
            }
            if (delayMs > 0) {
                AppLog.info("Charging signals disagree (plugged, not charging); waiting work re-queued in ${delayMs / 60_000} min")
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(settings.chargingOnly)
                .build()
            val request = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_WAITING to true))
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            prefs.edit().putLong(KEY_LAST_ENQUEUED_AT, System.currentTimeMillis()).apply()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME_WAITING, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * The plugged state the system's charging constraint is satisfied by. BatteryManager
         * has no property for it, so it is read from the sticky ACTION_BATTERY_CHANGED
         * broadcast - the receiver is null, nothing gets registered, the system's own last
         * broadcast is only read. A reading that cannot be had counts as not plugged, which
         * can only make the re-queue more eager, never suppress it.
         */
        private fun pluggedStatus(context: Context): Int =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        private const val PREFS_WAITING = "auto_update_waiting"
        private const val KEY_LAST_ENQUEUED_AT = "last_enqueued_at"

        /** Drops the pacing timestamp, for when no waiting work stands anymore. */
        private fun forgetWaitingEnqueuedAt(context: Context) {
            context.getSharedPreferences(PREFS_WAITING, Context.MODE_PRIVATE)
                .edit().remove(KEY_LAST_ENQUEUED_AT).apply()
        }

        /**
         * One check right now, whatever the schedule says. The result comes as a notification.
         * KEEP, not REPLACE: a manual check must not cancel a run that is already going -
         * its coroutine would be killed mid-download. If a manual run is already queued or
         * running, this one waits behind it (WorkManager chains unique work under KEEP) and
         * answers the same question moments later; the answer is the same notification the
         * manual run always gives.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)
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
                forgetWaitingEnqueuedAt(context)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoUpdateWorker>(settings.checkIntervalDays, TimeUnit.DAYS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, SHIZUKU_RETRY_STEP_MINUTES, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
