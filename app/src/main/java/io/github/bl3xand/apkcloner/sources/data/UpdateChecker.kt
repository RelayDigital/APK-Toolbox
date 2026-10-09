package io.github.bl3xand.apkcloner.sources.data

import android.content.Context
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.settings.AppSettings
import io.github.bl3xand.apkcloner.sources.core.MultiAppMultiError
import io.github.bl3xand.apkcloner.sources.core.RateLimitError
import io.github.bl3xand.apkcloner.sources.core.RepositoryRenamedError
import io.github.bl3xand.apkcloner.sources.core.effectiveMinUpdateAgeDays
import io.github.bl3xand.apkcloner.sources.core.errorText
import io.github.bl3xand.apkcloner.sources.core.isReleaseTooYoung
import io.github.bl3xand.apkcloner.sources.model.CheckUpdatesException
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.model.applyMinAgeSuppression
import io.github.bl3xand.apkcloner.sources.source.DEFAULT_FETCH_CONCURRENCY
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Asks the sources what their latest versions are, for one tracked app or for many at once. */
class UpdateChecker(private val context: Context, private val repo: SourcesRepository) {
    private val settings = repo.settings
    private val checkLock = Any()

    /** 0..1 while an update check runs, else null. */
    private val _progress = MutableStateFlow<Double?>(null)
    val progress: StateFlow<Double?> = _progress

    /** The newest state of an app from its source, not yet saved. Null while a rename is pending. */
    fun fetchUpdate(id: String): TrackedApp? {
        val current = repo.entry(id)?.app ?: return null
        if (current.hasPendingRepoRename) return null
        var fresh = SourceRegistry.getApp(repo.sourceOf(current), current.url, current.additionalSettings, currentApp = current)
        if (fresh.latestVersion != current.latestVersion &&
            isReleaseTooYoung(fresh.releaseDate, effectiveMinUpdateAgeDays(current.additionalSettings, settings))
        ) {
            // Too young to be offered yet (a guard against bad or hijacked releases).
            fresh = applyMinAgeSuppression(current, fresh)
        }
        fresh = if (current.preferredApkIndex < fresh.apkUrls.size) {
            fresh.copy(preferredApkIndex = current.preferredApkIndex)
        } else if (fresh.apkUrls.isNotEmpty()) {
            fresh.copy(preferredApkIndex = 0)
        } else fresh
        return fresh
    }

    private fun fetchUpdateWithHandshakeRetry(id: String): TrackedApp? {
        var attempt = 0
        while (true) {
            try {
                return fetchUpdate(id)
            } catch (e: SSLHandshakeException) {
                // Parallel handshakes with one host fail on some networks; try again shortly.
                if (attempt++ >= 2) throw e
                Thread.sleep(250L + Random.nextInt(501))
            }
        }
    }

    /** Checks one app and saves it; returns it only when its latest version changed. */
    fun checkUpdate(id: String): TrackedApp? {
        val current = repo.entry(id)?.app ?: return null
        AppLog.debug("Checking $id at ${current.url}")
        val fresh = try {
            fetchUpdate(id)
        } catch (e: Exception) {
            AppLog.warn("Check of $id failed: ${e.message ?: e}")
            throw e
        } ?: return null
        repo.saveApps(listOf(fresh))
        if (fresh.latestVersion != current.latestVersion) {
            AppLog.info("$id: new version ${fresh.latestVersion} (was ${current.latestVersion})")
            return fresh
        }
        AppLog.debug("$id: no change, latest is ${fresh.latestVersion}")
        return null
    }

    /** The interval of the app's background check, which the Sources tab shares. */
    val updateIntervalMinutes: Long get() = AppSettings(context).checkIntervalDays * 24 * 60

    /** Apps due for a check (all of them when [forceAll]), longest unchecked first. */
    fun getAppsSortedByUpdateCheckTime(onlyInstalledOrTrackOnly: Boolean = false, forceAll: Boolean = false): List<String> {
        val dueBefore = Instant.now().minusSeconds(updateIntervalMinutes * 60)
        return repo.all()
            .filter { forceAll || it.app.lastUpdateCheck == null || it.app.lastUpdateCheck.isBefore(dueBefore) }
            .filter { !onlyInstalledOrTrackOnly || it.app.installedVersion != null || it.app.settings.getBool(SettingKeys.TRACK_ONLY) }
            .sortedBy { it.app.lastUpdateCheck ?: Instant.EPOCH }
            .map { it.app.id }
    }

    /**
     * Checks many apps, a few at a time. Returns the apps with a new version; failures are
     * collected per app and thrown together as [CheckUpdatesException] at the end.
     */
    fun checkUpdates(
        specificIds: List<String>? = null,
        forceAll: Boolean = false,
        throwErrorsForRetry: Boolean = false,
    ): List<TrackedApp> = synchronized(checkLock) {
        val ids = specificIds?.toList()
            ?: getAppsSortedByUpdateCheckTime(settings.onlyCheckInstalledOrTrackOnlyApps, forceAll)
        val updates = Collections.synchronizedList(ArrayList<TrackedApp>())
        val fetched = Collections.synchronizedList(ArrayList<TrackedApp>())
        val failed = Collections.synchronizedList(ArrayList<TrackedApp>())
        val errors = MultiAppMultiError()
        val completed = AtomicInteger()
        val next = AtomicInteger()
        var fatal: Throwable? = null
        _progress.value = 0.0
        try {
            val workers = (0 until minOf(DEFAULT_FETCH_CONCURRENCY, ids.size)).map {
                Thread {
                    while (fatal == null) {
                        val index = next.getAndIncrement()
                        if (index >= ids.size) return@Thread
                        val id = ids[index]
                        val current = repo.entry(id)?.app
                        try {
                            val fresh = fetchUpdateWithHandshakeRetry(id)
                            if (fresh != null) {
                                fetched.add(fresh)
                                if (current != null && fresh.latestVersion != current.latestVersion &&
                                    repo.isAppUpdateable(fresh) && repo.entry(id)?.let(repo::hasSignerConflict) != true
                                ) {
                                    updates.add(fresh)
                                }
                            }
                        } catch (e: Throwable) {
                            if ((e is RateLimitError || e is IOException) && throwErrorsForRetry) {
                                fatal = e
                            } else if (e is RepositoryRenamedError) {
                                current?.let { repo.saveApps(listOf(it.copy(pendingRepoRenameUrl = e.newUrl))) }
                            } else {
                                synchronized(errors) { errors.add(id, e, appName = repo.entry(id)?.name) }
                                AppLog.warn("Update check failed for $id: ${errorText(e)}")
                                // Still counts as checked, or the background task would retry it
                                // every time it runs.
                                current?.let { failed.add(it.copy(lastUpdateCheck = Instant.now())) }
                            }
                        }
                        _progress.value = completed.incrementAndGet().toDouble() / ids.size
                    }
                }.apply { start() }
            }
            workers.forEach { it.join() }
            fatal?.let { throw it }
            if (fetched.isNotEmpty()) repo.saveApps(fetched.toList(), reuseInstalledInfo = true)
            if (failed.isNotEmpty()) {
                repo.saveApps(failed.toList(), attemptToCorrectInstallStatus = false, reuseInstalledInfo = true)
            }
            if (errors.idsByErrorString.isNotEmpty()) throw CheckUpdatesException(updates.toList(), errors)
            updates.toList()
        } finally {
            _progress.value = null
        }
    }
}
