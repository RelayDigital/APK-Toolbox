package io.github.bl3xand.apkcloner.testing

import android.content.Context
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import java.util.ArrayDeque

/**
 * A debug-only window into the app's own state, for instrumentation tests - which run inside
 * the app's process, so they can reach this object directly instead of polling the UI or the
 * file system. Every method is a no-op unless [enabled] is on, and only [enable] can turn it
 * on, and only with an argument that says the caller is a debug build - so in a release build
 * every hook call does nothing and the state stays empty, whatever R8 makes of this object.
 * No Android framework lookup is involved in the gate: the tests run in-process, so the
 * instrumentation flips the switch by a direct call with its own BuildConfig.DEBUG.
 *
 * What is recorded, where the hooks are wired in, and how to use it from a test:
 * docs/test-hooks.md.
 */
object TestHooks {

    /** The pure, Android-free state behind the hooks, unit-tested as is. */
    internal val state = State()

    /** Records only when [enabled]; a no-op otherwise (and always in a release build). */
    fun notificationPosted(channel: String, id: Int, title: String) {
        if (!enabled) return
        state.recordNotification(NotificationRecord(channel, id, title))
    }

    /** Records only when [enabled]; a no-op otherwise (and always in a release build). */
    fun installStarted(packageName: String) {
        if (!enabled) return
        state.recordInstallStarted(packageName)
    }

    /** Records only when [enabled]; a no-op otherwise (and always in a release build). */
    fun installFinished(packageName: String) {
        if (!enabled) return
        state.recordInstallFinished(packageName)
    }

    /** Records only when [enabled]; a no-op otherwise (and always in a release build). */
    fun backgroundCheckStarted() {
        if (!enabled) return
        state.recordBackgroundCheckStarted()
    }

    /** Records only when [enabled]; a no-op otherwise (and always in a release build). */
    fun backgroundCheckFinished() {
        if (!enabled) return
        state.recordBackgroundCheckFinished()
    }

    /** The last notifications posted, oldest first, at most [NOTIFICATION_RING_SIZE] of them. */
    val notifications: List<NotificationRecord> get() = if (enabled) state.notifications else emptyList()

    /** The first record of a notification with this [id], or null if none was posted. */
    fun notification(id: Int): NotificationRecord? = notifications.firstOrNull { it.id == id }

    /** Whether a notification with this [id] was posted since the last [resetAppState]. */
    fun hasNotification(id: Int): Boolean = notifications.any { it.id == id }

    /** Package names with an install in flight right now. */
    val currentInstalls: Set<String> get() = if (enabled) state.currentInstalls else emptySet()

    /** How many installs have finished since the last [resetAppState]. */
    val finishedInstallCount: Int get() = if (enabled) state.finishedInstallCount else 0

    /** How many background checks have started since the last [resetAppState]. */
    val backgroundCheckRunsStarted: Int get() = if (enabled) state.backgroundCheckRunsStarted else 0

    /** How many background checks have finished since the last [resetAppState]. */
    val backgroundCheckRunsFinished: Int get() = if (enabled) state.backgroundCheckRunsFinished else 0

    /** Wall clock of the last finished background check, epoch millis; 0 when none did. */
    val lastBackgroundCheckFinishedAt: Long get() = if (enabled) state.lastBackgroundCheckFinishedAt else 0L

    /**
     * Puts the app into the state a test wants to start from: no tracked apps, no in-flight
     * downloads or remembered check errors, no notifications recorded, and the app's own
     * once-only self-tracking already considered done - a fresh install would otherwise add
     * itself to the tracked apps on the first reload, racing whatever the test is doing.
     * Only in-memory state is touched; the tests wipe the app's files themselves
     * (BaseUiTest.wipeAppData), so this belongs after that wipe, not before it.
     */
    fun resetAppState(context: Context) {
        if (!enabled) return
        state.reset()
        val app = context.applicationContext
        val repo = SourcesRepository.get(app)
        // Nothing in flight, and no error of a previous test's checks left standing.
        repo.downloads.value.keys.toList().forEach { repo.setDownload(it, null) }
        repo.checkErrors.value.keys.toList().forEach { repo.setCheckError(it, null) }
        // The store itself, the way removing the apps from the list does.
        repo.removeApps(repo.all().map { it.app.id })
        // Considered done, so a reload never races the test by adding the app itself.
        repo.settings.selfTracked = true
    }

    /**
     * The instrumentation's switch. [debuggable] is the caller's own BuildConfig.DEBUG - the
     * tests build against the app's debug variant, so the two agree, and a release build
     * cannot supply a true one. No call to this is in the app's own code: only the test
     * instrumentation enables the hooks, and it is not part of what ships.
     */
    fun enable(debuggable: Boolean) {
        if (debuggable) enabled = true
    }

    /** Whether anything is recorded; false in every release build. */
    @Volatile
    var enabled: Boolean = false
        private set

    /** How many notifications [notifications] keeps. */
    const val NOTIFICATION_RING_SIZE = 32

    /** One posted notification, as a test wants to assert on it. */
    data class NotificationRecord(val channel: String, val id: Int, val title: String)

    /** The Android-free state behind the hooks, unit-tested as is. */
    internal class State {
        private val lock = Any()
        private val ring = ArrayDeque<NotificationRecord>()
        private val installing = HashSet<String>()
        private var finishedInstalls = 0
        private var startedChecks = 0
        private var finishedChecks = 0
        private var lastFinishedCheckAt = 0L

        val notifications: List<NotificationRecord> get() = synchronized(lock) { ring.toList() }
        val currentInstalls: Set<String> get() = synchronized(lock) { installing.toSet() }
        val finishedInstallCount: Int get() = synchronized(lock) { finishedInstalls }
        val backgroundCheckRunsStarted: Int get() = synchronized(lock) { startedChecks }
        val backgroundCheckRunsFinished: Int get() = synchronized(lock) { finishedChecks }
        val lastBackgroundCheckFinishedAt: Long get() = synchronized(lock) { lastFinishedCheckAt }

        fun recordNotification(record: NotificationRecord) = synchronized(lock) {
            ring.addLast(record)
            while (ring.size > NOTIFICATION_RING_SIZE) ring.removeFirst()
        }

        fun recordInstallStarted(packageName: String) = synchronized(lock) {
            installing.add(packageName)
        }

        fun recordInstallFinished(packageName: String) = synchronized(lock) {
            if (installing.remove(packageName)) finishedInstalls++
        }

        fun recordBackgroundCheckStarted() = synchronized(lock) {
            startedChecks++
        }

        fun recordBackgroundCheckFinished() = synchronized(lock) {
            finishedChecks++
            lastFinishedCheckAt = System.currentTimeMillis()
        }

        fun reset() = synchronized(lock) {
            ring.clear()
            installing.clear()
            finishedInstalls = 0
            startedChecks = 0
            finishedChecks = 0
            lastFinishedCheckAt = 0L
        }
    }
}