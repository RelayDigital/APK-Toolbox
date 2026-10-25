package io.github.bl3xand.apkcloner.install

import io.github.bl3xand.apkcloner.testing.TestHooks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes the install phase of every update driver in the process: the clone worker
 * (AutoUpdateWorker), the sources background pass (SourcesBackground with its retries)
 * and an obtain asked for on screen (SourcesViewModel.obtain). Downloads stay parallel;
 * what is coordinated is the hand-off to the system installer and the wait for its
 * answer, so two sessions are never created for one package at once and prompts and
 * progress notifications do not pile on top of each other.
 *
 * Three locks, and they are always taken in this order:
 *
 * 1. The foreground queue: an install started from the app's window holds it for its
 *    whole install, so two prompts never stack and one open confirmation blocks the
 *    next foreground install, not anything else.
 * 2. The batch mutex: a background batch (a whole worker pass) holds it for its entire
 *    install phase. A foreground install takes it only for the hand-off itself, so a
 *    foreground obtain that arrives while a batch is installing waits for the batch to
 *    finish instead of interleaving its notifications - while a confirmation the user
 *    leaves open for minutes holds up no background batch.
 * 3. The per-package mutex: held for the whole install of that package, waits included,
 *    so the same package is never installed twice at once, whatever the driver.
 *
 * The one crossing the others cannot avoid is the foreground commit: it holds the
 * package's own lock (3) and then takes the batch mutex (2) for the hand-off, while a
 * background batch holds (2) and takes (3) for each package it installs. Taken as a
 * plain wait, that crossing deadlocks on one package: the foreground holds the package
 * and waits for the batch, the batch holds the batch and waits for the package - and a
 * Mutex wait is not interrupted by anything but the coroutine's own cancellation. So a
 * foreground commit does not wait out a batch it cannot join: after [HANDOFF_TIMEOUT_MS]
 * it gives up the batch mutex and hands off anyway, outside the batches, one prompt that
 * interleaves rather than two installs that never move again. The crossing is what the
 * samePackageForegroundVsBackground test pins down.
 *
 * Background installs run inside [backgroundBatch] and so already hold the batch mutex;
 * they must not call [backgroundBatch] again (the mutex is not reentrant). Pure Kotlin,
 * like NetworkWaiter: no Android types, so the rules are unit-tested as is.
 */
object InstallCoordinator {

    private val registry = Mutex()
    private val perPackage = HashMap<String, Mutex>()
    private val batchMutex = Mutex()
    private val foregroundQueue = Mutex()

    /**
     * How long a foreground commit waits to join the batch mutex before handing off without
     * it. Long enough that an ordinary batch's commit - staging files into the session and
     * committing it, no waits for answers inside - is normally waited out; short enough that
     * a batch stuck on a package's per-package lock (the deadlock side of the lock crossing)
     * costs the foreground minutes, not the rest of its life. The waits a user can hold open
     * for minutes are the answer waits, and those run outside every commit. Internal and a
     * var so the tests can shrink it.
     */
    internal var handoffTimeoutMs = 30_000L

    private val _installs = MutableStateFlow(0)

    /** How many installs are in flight right now, drivers of every kind added together. */
    val installs: StateFlow<Int> = _installs.asStateFlow()

    /** Whether any install is running; what the "already busy" guards check. */
    val isInstalling: Boolean get() = _installs.value > 0

    /**
     * Wraps the whole install phase of a background batch: the clone loop of the worker
     * or the install pass of the sources background run. While it is held, a foreground
     * install of any package waits for its turn instead of interleaving.
     */
    suspend fun <T> backgroundBatch(block: suspend () -> T): T = batchMutex.withLock { block() }

    /** What [installing] hands to its block: the only place an install may be committed from. */
    class Phase internal constructor(private val background: Boolean) {
        /**
         * Runs [block] as the hand-off to the system installer - staging the files and
         * committing the session. One at a time process-wide: a foreground hand-off
         * waits out any running background batch first (up to the hand-off timeout - see
         * the lock crossing above; past that it hands off without the batch rather than
         * deadlocking on a batch that waits on this very package). Must not suspend for
         * long: the waits for the installer's answer belong outside it, in the caller's
         * block.
         */
        suspend fun <R> commit(block: suspend () -> R): R =
            if (background) block()
            else withTimeoutOrNull(handoffTimeoutMs) { batchMutex.lock() }.let { joined ->
                try {
                    block()
                } finally {
                    if (joined != null) batchMutex.unlock()
                }
            }
    }

    /**
     * Runs [block] as the install of [packageName]: only one install of that package at
     * a time, whatever driver asks for it. [background] installs must run inside a
     * [backgroundBatch]. The hand-off to the system goes through [Phase.commit] inside
     * the block; everything after it - the waits for the answer included - runs under
     * the package's lock but under no batch lock. Not reentrant: an install must not
     * start another install of the same package inside itself.
     */
    suspend fun <T> installing(packageName: String, background: Boolean, block: suspend Phase.() -> T): T {
        // Entries stay for the process's lifetime: a waiter can already be queued on a
        // package's mutex when its current install ends, and dropping the entry then
        // would let a fresh mutex bypass it.
        val packageMutex = registry.withLock { perPackage.getOrPut(packageName) { Mutex() } }
        val run: suspend Phase.() -> T = block
        return if (background) {
            // The enclosing batch holds the global queue; only this package's turn is taken.
            packageMutex.withLock {
                _installs.update { it + 1 }
                TestHooks.installStarted(packageName)
                try {
                    run(Phase(background = true))
                } finally {
                    TestHooks.installFinished(packageName)
                    _installs.update { it - 1 }
                }
            }
        } else {
            // One foreground install at a time, prompts included; the hand-off then also
            // waits out a running background batch, but the waits for the answer do not.
            foregroundQueue.withLock {
                packageMutex.withLock {
                    _installs.update { it + 1 }
                    TestHooks.installStarted(packageName)
                    try {
                        run(Phase(background = false))
                    } finally {
                        TestHooks.installFinished(packageName)
                        _installs.update { it - 1 }
                    }
                }
            }
        }
    }
}