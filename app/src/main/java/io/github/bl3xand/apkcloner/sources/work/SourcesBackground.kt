package io.github.bl3xand.apkcloner.sources.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.bl3xand.apkcloner.sources.core.MultiAppMultiError
import io.github.bl3xand.apkcloner.sources.core.RateLimitError
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import io.github.bl3xand.apkcloner.sources.install.SourcesInstaller
import io.github.bl3xand.apkcloner.sources.model.CheckUpdatesException
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** The background pass over tracked apps: check, notify, and install what can go in silently. */
object SourcesBackground {
    private const val MAX_ATTEMPTS = 4
    private const val MAX_RETRY_WAIT_SECONDS = 30
    private const val TRANSPORT_RETRY_WAIT_SECONDS = 15 * 60
    /** How long a background run waits for a network that is still coming up. */
    private const val NETWORK_WAIT_MILLIS = 30_000L
    const val KEY_IDS = "toCheckIds"
    const val KEY_ATTEMPTS = "toCheckAttempts"

    private fun network(context: Context): NetworkCapabilities? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork ?: return null)
    }

    /**
     * The network the app's HTTP client would use right now: some network has to be active
     * and claim the internet capability, and while a network is still coming up that can
     * take a moment. A run that starts in that window waits here for it; the capabilities
     * seen usable are returned, or null if none arrived.
     */
    private suspend fun waitForNetwork(context: Context): NetworkCapabilities? {
        var usable: NetworkCapabilities? = null
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val waiter = NetworkWaiter(
            isUsable = {
                val capabilities = network(context)
                val good = capabilities != null &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (good) usable = capabilities
                good
            },
            observe = { onChange -> watchForNetworkChange(manager, onChange) },
        )
        return if (waiter.awaitUsable(NETWORK_WAIT_MILLIS)) usable else null
    }

    /** Reports changes of the network the app would use to [onChange] until the returned function is called. */
    private fun watchForNetworkChange(manager: ConnectivityManager, onChange: () -> Unit): () -> Unit {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = onChange()

            override fun onLost(network: Network) = onChange()
        }
        // Watching the network the app would use asks for no more than the ACCESS_NETWORK_STATE
        // the app already has. It reports every change of that network - another one becoming
        // it (a validating network does once it is ready), its capabilities changing, or it
        // being lost for another to take over - and each report is re-checked.
        manager.registerDefaultNetworkCallback(callback)
        return { manager.unregisterNetworkCallback(callback) }
    }

    /**
     * [retry] carries the apps of a retry run with their attempt counts; a retry only checks,
     * the regular run also installs. [forceAll] - a check asked for by hand - ignores the per-app
     * schedule and reports every update that is waiting, not only those found just now.
     * With [only], nothing but those apps is looked at. Returns whether there was anything to
     * report or install.
     */
    suspend fun run(
        context: Context,
        retry: List<Pair<String, Int>>? = null,
        forceAll: Boolean = false,
        only: Set<String>? = null,
    ): Boolean {
        val repo = SourcesRepository.get(context)
        val installer = SourcesInstaller.get(context)
        val settings = repo.settings
        repo.loadApps()
        // WorkManager can start the run while a network is still validating, before it
        // claims the internet capability; give it a moment before calling this no network.
        var capabilities = network(context)
        if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            capabilities = waitForNetwork(context)
            if (capabilities == null) {
                AppLog.info("BG update task: No network.")
                return false
            }
        }
        val toCheck = retry ?: repo.updates.getAppsSortedByUpdateCheckTime(
            settings.onlyCheckInstalledOrTrackOnlyApps, forceAll,
        ).filter { only == null || it in only }.map { it to 0 }

        val networkRestricted = settings.bgUpdatesOnWiFiOnly &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val chargingRestricted = settings.bgUpdatesWhileChargingOnly &&
            !context.getSystemService(BatteryManager::class.java).isCharging
        val canInstall = !networkRestricted && !chargingRestricted
        val silentlyInstallable = ArrayList<String>()
        var reported = false

        if (toCheck.isNotEmpty()) {
            AppLog.info("BG update task: Started (${toCheck.size}).")
            var updates: List<TrackedApp> = emptyList()
            var errors: MultiAppMultiError? = null
            val toReport = MultiAppMultiError()
            val toRetry = ArrayList<Pair<String, Int>>()
            var retryAfterSeconds = 0
            SourcesNotifications.checking(context, toCheck.size)
            try {
                updates = repo.updates.checkUpdates(specificIds = toCheck.map { it.first })
            } catch (e: CheckUpdatesException) {
                updates = e.updates
                errors = e.errors
                for ((id, error) in e.errors.rawErrors) {
                    val attempt = toCheck.firstOrNull { it.first == id }?.second ?: 0
                    if (attempt < MAX_ATTEMPTS) {
                        toRetry.add(id to attempt + 1)
                        var wait = when (error) {
                            is RateLimitError -> error.remainingMinutes * 60
                            is IOException -> TRANSPORT_RETRY_WAIT_SECONDS
                            else -> attempt + 1
                        }
                        if (wait > MAX_RETRY_WAIT_SECONDS && error !is RateLimitError) wait = MAX_RETRY_WAIT_SECONDS
                        if (wait > retryAfterSeconds) retryAfterSeconds = wait
                    } else if (error !is RateLimitError) {
                        toReport.add(id, error, appName = e.errors.appIdNames[id])
                    }
                }
            } finally {
                SourcesNotifications.cancel(context, SourcesNotifications.ID_CHECKING)
            }
            if (toRetry.isNotEmpty()) scheduleRetry(context, toRetry, retryAfterSeconds)

            val notify = ArrayList<TrackedApp>()
            val notifyTrackOnly = ArrayList<TrackedApp>()
            // Asked by hand, the answer covers what was already known to be waiting as well.
            val waiting = if (!forceAll) emptyList() else repo.findAppIdsWithPendingUpdates(installedOnly = true)
                .filter { id -> (only == null || id in only) && updates.none { it.id == id } }.mapNotNull { repo.entry(it)?.app }
            for (update in updates + waiting) {
                if (canInstall && installer.canInstallSilentlyInBackground(update)) {
                    silentlyInstallable.add(update.id)
                } else if (!update.settings.getBool(SettingKeys.SKIP_UPDATE_NOTIFICATIONS)) {
                    if (update.settings.getBool(SettingKeys.TRACK_ONLY)) notifyTrackOnly.add(update) else notify.add(update)
                }
            }
            reported = notify.isNotEmpty() || notifyTrackOnly.isNotEmpty()
            if (notify.isNotEmpty()) SourcesNotifications.updatesAvailable(context, notify)
            if (notifyTrackOnly.isNotEmpty()) SourcesNotifications.updatesAvailable(context, notifyTrackOnly, trackOnly = true)
            if (toReport.rawErrors.isNotEmpty() && errors != null) {
                for ((text, ids) in toReport.idsByErrorString) {
                    SourcesNotifications.checkError(context, errors.errorsAppsString(text, ids), 100 + abs(text.hashCode() % 10_000))
                }
            }
        } else {
            AppLog.info("BG update task: No apps due for checking.")
        }

        if (retry != null) return reported
        if (canInstall && settings.enableBackgroundUpdates) {
            // Updates found earlier and still waiting are picked up as well.
            for (id in repo.findAppIdsWithPendingUpdates(installedOnly = true)) {
                if (id in silentlyInstallable || (only != null && id !in only)) continue
                val app = repo.entry(id)?.app ?: continue
                if (installer.canInstallSilentlyInBackground(app)) silentlyInstallable.add(id)
            }
        }
        if (silentlyInstallable.isEmpty()) return reported
        AppLog.info("BG install task: Installing ${silentlyInstallable.size} apps silently.")
        try {
            installer.downloadAndInstallLatestApps(silentlyInstallable, prompts = null, forceSerialDownloads = true)
        } catch (e: MultiAppMultiError) {
            for ((text, ids) in e.idsByErrorString) {
                SourcesNotifications.checkError(context, e.errorsAppsString(text, ids), 200 + abs(text.hashCode() % 10_000))
            }
        }
        return true
    }

    private fun scheduleRetry(context: Context, toRetry: List<Pair<String, Int>>, afterSeconds: Int) {
        val settings = SourcesRepository.get(context).settings
        AppLog.info("BG update task: Scheduling retry in ${afterSeconds}s (${toRetry.size} to retry).")
        val request = OneTimeWorkRequestBuilder<SourcesRetryWorker>()
            .setInitialDelay(afterSeconds.toLong(), TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (settings.bgUpdatesOnWiFiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(settings.bgUpdatesWhileChargingOnly)
                    .build(),
            )
            .setInputData(
                workDataOf(
                    KEY_IDS to toRetry.map { it.first }.toTypedArray(),
                    KEY_ATTEMPTS to toRetry.map { it.second }.toIntArray(),
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
