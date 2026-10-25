package io.github.bl3xand.apkcloner.sources.data

import android.content.Context
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.settings.AppSettings
import io.github.bl3xand.apkcloner.sources.core.CHECK_TIMEOUT_SECONDS
import io.github.bl3xand.apkcloner.sources.core.MultiAppMultiError
import io.github.bl3xand.apkcloner.sources.core.RateLimitError
import io.github.bl3xand.apkcloner.sources.core.ReleaseBranchGoneError
import io.github.bl3xand.apkcloner.sources.core.RepositoryRenamedError
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.effectiveMinUpdateAgeDays
import io.github.bl3xand.apkcloner.sources.core.errorText
import io.github.bl3xand.apkcloner.sources.core.isReleaseTooYoung
import io.github.bl3xand.apkcloner.sources.model.CheckUpdatesException
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.model.applyMinAgeSuppression
import io.github.bl3xand.apkcloner.sources.model.carryPreferredFile
import io.github.bl3xand.apkcloner.sources.source.DEFAULT_FETCH_CONCURRENCY
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import io.github.bl3xand.apkcloner.testing.TestHooks
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Asks the sources what their latest versions are, for one tracked app or for many at once. */
class UpdateChecker(private val context: Context, private val repo: SourcesRepository) {
    private val settings = repo.settings
    private val checkLock = Any()

    /**
     * How long one app's check may take before the pass gives up on it. A source that answers
     * not at all (a dead tunnel, a silent drop) would otherwise hold its worker - and with it
     * the whole pass and every later one queued behind [checkLock] - for as long as the socket
     * timeouts take, which can be minutes.
     */
    private val checkTimeoutMs = CHECK_TIMEOUT_SECONDS * 1000

    /** 0..1 while an update check runs, else null. */
    private val _progress = MutableStateFlow<Double?>(null)
    val progress: StateFlow<Double?> = _progress

    /** The newest state of an app from its source, not yet saved. Null while a rename is pending. */
    fun fetchUpdate(id: String): TrackedApp? {
        val current = repo.entry(id)?.app ?: return null
        if (current.hasPendingRepoRename) return null
        var fresh = try {
            SourceRegistry.getApp(repo.sourceOf(current), current.url, current.additionalSettings, currentApp = current)
        } catch (e: ReleaseBranchGoneError) {
            // The kind of file this app follows is not offered any more, and the source has no
            // list to choose another from here. Nothing is said to have failed - that would put
            // the app on the error-and-retry path - it simply needs its kind chosen again.
            AppLog.info("$id: the followed file kind is gone (${repo.sourceOf(current).sourceIdentifier})")
            return current.copy(releaseBranchGone = true, lastUpdateCheck = Instant.now())
        }
        if (fresh.latestVersion != current.latestVersion &&
            isReleaseTooYoung(fresh.releaseDate, effectiveMinUpdateAgeDays(current.additionalSettings, settings))
        ) {
            // Too young to be offered yet (a guard against bad or hijacked releases).
            fresh = applyMinAgeSuppression(current, fresh)
        }
        val source = repo.sourceOf(current)
        val wasGone = current.releaseBranchGone
        fresh = carryPreferredFile(
            current, fresh, source::releaseBranchKeyOf,
            settingsBranchKey = source.followedBranchKey(current.additionalSettings),
        )
        if (!wasGone && fresh.releaseBranchGone) {
            AppLog.warn("$id: the followed release branch is gone; another file has to be chosen")
        } else if (wasGone && !fresh.releaseBranchGone) {
            AppLog.info("$id: the followed release branch is back")
        }
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

    /**
     * One app's fetch, run on its own daemon thread so a source that never answers holds only
     * it: the caller gives up after [checkTimeoutMs] and the app says so, instead of the whole
     * pass - or the one check behind a single row - freezing on a dead network. The abandoned
     * thread keeps running until its socket gives up; whatever it still writes is discarded.
     * One thread per fetch, never a shared pool: hung fetches must not starve the next ones.
     */
    private fun fetchUpdateWithinTimeout(id: String): TrackedApp? {
        val future = CompletableFuture<TrackedApp?>()
        Thread({
            try {
                future.complete(fetchUpdateWithHandshakeRetry(id))
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }, "update-check").apply { isDaemon = true }.start()
        return try {
            future.get(checkTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw SourceError(code = "CHECK_TIMED_OUT", unexpected = true)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Checks one app and saves it; returns it only when its latest version changed. */
    fun checkUpdate(id: String): TrackedApp? {
        val current = repo.entry(id)?.app ?: return null
        AppLog.debug("Checking $id at ${current.url}")
        val fresh = try {
            // The single check behind a row obeys the same timeout as the whole pass: a source
            // that never answers says so instead of hanging the row's download or options save.
            fetchUpdateWithinTimeout(id)
        } catch (e: Exception) {
            AppLog.warn("Check of $id failed: ${e.message ?: e}")
            // A repository that moved has a notice of its own.
            if (e !is RepositoryRenamedError) repo.setCheckError(id, checkErrorText(e))
            throw e
        } ?: return null
        repo.setCheckError(id, null)
        repo.saveApps(listOf(fresh), preserveUserFields = true)
        if (fresh.latestVersion != current.latestVersion) {
            AppLog.info("$id: new version ${fresh.latestVersion} (was ${current.latestVersion})")
            return fresh
        }
        AppLog.debug("$id: no change, latest is ${fresh.latestVersion}")
        return null
    }

    /** The interval of the app's background check, which the Sources tab shares. */
    val updateIntervalMinutes: Long get() = AppSettings(context).checkIntervalDays * 24 * 60

    /**
     * What a row says when its check failed. A network that does not answer - DNS that resolves
     * nothing, a socket that never opens or never speaks, our own give-up time - is one thing
     * to the user no matter which layer said it, so its many raw shapes (the system's own
     * "Unable to resolve host ...", "Connection timed out", and our timeout error) all say the
     * same localized line; the raw words stay in the log. Everything else a source can fail
     * with keeps its own message, which does mean something on its own.
     */
    private fun checkErrorText(e: Throwable): String =
        if (e is IOException || e is SourceError && e.code == "CHECK_TIMED_OUT") {
            Tr.get("checkTimedOut")
        } else {
            errorText(e)
        }

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
        // What a whole check pass took, said to the test hooks (empty unless the
        // instrumentation enabled them) as a started/finished pair.
        TestHooks.checkStarted()
        try {
            checkUpdatesInside(specificIds, forceAll, throwErrorsForRetry)
        } finally {
            TestHooks.checkFinished()
        }
    }

    private fun checkUpdatesInside(
        specificIds: List<String>?,
        forceAll: Boolean,
        throwErrorsForRetry: Boolean,
    ): List<TrackedApp> {
        val wanted = specificIds?.toList()
            ?: getAppsSortedByUpdateCheckTime(settings.onlyCheckInstalledOrTrackOnlyApps, forceAll)
        // An app whose source has to be signed in to first cannot be checked; it says so on its
        // row, and failing on it here would only repeat that at every check.
        val ids = wanted.filter { id -> repo.entry(id)?.let { runCatching { repo.sourceOf(it.app).signInNote }.getOrNull() } == null }
        if (ids.size < wanted.size) AppLog.debug("Skipped ${wanted.size - ids.size} app(s) whose source needs signing in")
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
                            // A source that never answers is given up on in time (its own daemon
                            // thread keeps hanging on the socket alone), so the pass - and every
                            // later one queued behind the same lock - always finishes.
                            val fresh = fetchUpdateWithinTimeout(id)
                            repo.setCheckError(id, null)
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
                                repo.setCheckError(id, checkErrorText(e))
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
            if (fetched.isNotEmpty()) repo.saveApps(fetched.toList(), reuseInstalledInfo = true, preserveUserFields = true)
            if (failed.isNotEmpty()) {
                repo.saveApps(failed.toList(), attemptToCorrectInstallStatus = false, reuseInstalledInfo = true, preserveUserFields = true)
            }
            if (errors.idsByErrorString.isNotEmpty()) throw CheckUpdatesException(updates.toList(), errors)
            return updates.toList()
        } finally {
            _progress.value = null
        }
    }
}
