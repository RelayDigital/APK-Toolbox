package io.github.bl3xand.apkcloner.sources.net

import java.util.concurrent.locks.ReentrantLock

/**
 * What a download does about a ".part" file that may belong to another, live download: it
 * waits for write ownership instead of appending over a file the other one still holds. The
 * old size-polling heuristic declared a merely-slow download dead after one 7-second stall -
 * far below the 60-second read timeout a healthy writer can sit in - and then corrupted the
 * file with a second append-mode writer.
 *
 * Pure decision logic: the clock, the sleeping and the lock state come in as functions, so
 * the whole wait can be exercised in unit tests without threads or files.
 */
class PartWait(
    private val now: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val pollMs: Long = POLL_MS,
    private val stallMs: Long = STALL_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
) {
    enum class Outcome {
        /** Write ownership won cleanly; the caller owns the ".part" until it releases it. */
        ACQUIRED,

        /**
         * Write ownership won, but the holder before it had gone silent for longer than the
         * stall threshold: what it left in the ".part" is suspect, and the caller restarts
         * from byte zero instead of resuming on top of it.
         */
        ACQUIRED_AFTER_STALL,

        /** The caller's cancellation was noticed while waiting. */
        CANCELLED,

        /** The holder stopped growing its file and never released; not writable, not dead. */
        TIMED_OUT,
    }

    /**
     * Waits for [tryAcquire] to win write ownership. Between attempts the caller's
     * [cancelled] flag is honoured at once, and a holder whose ".part" keeps growing
     * ([partSize], null when there is none yet) is never declared dead - growth means the
     * writer is alive however slow. Only a holder that is both past [maxWaitMs] and has not
     * grown its file for [stallMs] - which sits above the read timeout, so no healthy
     * blocked read can look like a stall - gives up as [Outcome.TIMED_OUT]. Ownership that
     * arrives only after such a stall is reported as [Outcome.ACQUIRED_AFTER_STALL].
     */
    fun await(tryAcquire: () -> Boolean, cancelled: () -> Boolean, partSize: () -> Long?): Outcome {
        val startedAt = now()
        var lastSize = partSize()
        var lastGrowthAt = startedAt
        while (true) {
            if (tryAcquire()) {
                return if (now() - lastGrowthAt >= stallMs) Outcome.ACQUIRED_AFTER_STALL else Outcome.ACQUIRED
            }
            if (cancelled()) return Outcome.CANCELLED
            val sizeNow = partSize()
            if (sizeNow != null && sizeNow != lastSize) {
                lastSize = sizeNow
                lastGrowthAt = now()
            }
            val nowMs = now()
            if (nowMs - startedAt >= maxWaitMs && nowMs - lastGrowthAt >= stallMs) {
                return Outcome.TIMED_OUT
            }
            sleeper(pollMs)
        }
    }

    companion object {
        /** How often the lock is retried while another writer is active. */
        const val POLL_MS = 7_000L

        /**
         * No growth for this long means the holder is wedged: a live read returns or throws
         * within the read timeout (60 s), and a retry's delay is far shorter.
         */
        const val STALL_MS = 70_000L

        /** The whole wait is capped; past it (with a stall) the caller reports the conflict. */
        const val MAX_WAIT_MS = 5 * 60_000L
    }
}

/**
 * Write ownership of a ".part" file, one lock per path. An in-process registry rather than an
 * OS file lock on purpose: the JVM's FileChannel locks are held on behalf of the whole
 * process (see java.nio.channels.FileLock), so two threads of this app - and the manifest
 * runs a single process, so every writer is a thread of it - would not exclude each other.
 * The [ReentrantLock] does. The registry entry is dropped once unlocked, so paths do not
 * pile up; a thread only ever holds one at a time (splits download one by one), so there is
 * no lock ordering to deadlock on.
 */
object PartFileLocks {
    private val locks = HashMap<String, ReentrantLock>()

    /** Takes the path's lock when free; false right away when another thread holds it. */
    @Synchronized
    fun tryAcquire(path: String): Boolean = locks.getOrPut(path) { ReentrantLock() }.tryLock()

    /** Whether this thread holds the path's lock. */
    @Synchronized
    fun isHeld(path: String): Boolean = locks[path]?.isHeldByCurrentThread == true

    /** Gives the lock back; only the owning thread may call this. */
    @Synchronized
    fun release(path: String) {
        val lock = locks[path] ?: return
        if (!lock.isHeldByCurrentThread) return
        lock.unlock()
        if (!lock.isLocked) locks.remove(path)
    }
}