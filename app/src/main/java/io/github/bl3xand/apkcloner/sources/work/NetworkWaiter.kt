package io.github.bl3xand.apkcloner.sources.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits for a usable network. A background job that fires just as a network comes up can
 * start while that network is still validating, before it claims the capability an HTTP
 * client needs: a single check at that moment reports no network where there is one a few
 * seconds later. The state is therefore checked once up front and then on every reported
 * change, until the timeout.
 *
 * Kept free of Android types so plain JVM tests can drive it with fakes: [isUsable] and
 * [observe] are the app's wiring around ConnectivityManager (see SourcesBackground).
 */
class NetworkWaiter(
    /** Whether the network an HTTP client would use right now is usable. */
    private val isUsable: () -> Boolean,
    /**
     * Starts watching for network changes and calls the given function, from any thread,
     * whenever the state may have become usable; returns what stops the watching.
     */
    private val observe: (onChange: () -> Unit) -> () -> Unit,
) {
    /** Whether the network is usable now or becomes so within [timeoutMillis]. The watcher is stopped either way. */
    suspend fun awaitUsable(timeoutMillis: Long): Boolean {
        if (isUsable()) return true
        val usable = CompletableDeferred<Unit>()
        val stop = observe { if (isUsable()) usable.complete(Unit) }
        try {
            // The network may have turned usable between the first check and the watcher
            // being in place; the watcher only reports changes from then on.
            if (isUsable()) return true
            return withTimeoutOrNull(timeoutMillis) {
                usable.await()
                true
            } ?: false
        } finally {
            stop()
        }
    }
}