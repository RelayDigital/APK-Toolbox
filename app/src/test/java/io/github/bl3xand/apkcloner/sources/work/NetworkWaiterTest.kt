package io.github.bl3xand.apkcloner.sources.work

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The waiting half of the fix for the background check that met a network still validating:
// NetworkWaiter is plain Kotlin, so the ConnectivityManager wiring is left out and faked here.

class NetworkWaiterTest {

    @Test
    fun usableAtOnce() {
        var registrations = 0
        val waiter = NetworkWaiter(isUsable = { true }, observe = {
            registrations++
            { }
        })
        val result = runBlocking { waiter.awaitUsable(1_000) }
        assertTrue(result)
        // A network that is already up needs no watcher at all.
        assertEquals(0, registrations)
    }

    @Test
    fun usableAfterAChange() {
        var usable = false
        var stopped = false
        val waiter = NetworkWaiter(
            isUsable = { usable },
            observe = { onChange ->
                val stop: () -> Unit = { stopped = true }
                Thread {
                    Thread.sleep(50)
                    usable = true
                    onChange()
                }.start()
                stop
            },
        )
        val result = runBlocking { waiter.awaitUsable(5_000) }
        assertTrue(result)
        assertTrue(stopped)
    }

    @Test
    fun usableAfterAChangeThatChangedNothing() {
        var usable = false
        var stopped = false
        val waiter = NetworkWaiter(
            isUsable = { usable },
            observe = { onChange ->
                val stop: () -> Unit = { stopped = true }
                Thread {
                    Thread.sleep(50)
                    onChange() // a change that leaves the network as unusable as before
                    Thread.sleep(50)
                    usable = true
                    onChange()
                }.start()
                stop
            },
        )
        val result = runBlocking { waiter.awaitUsable(5_000) }
        assertTrue(result)
        assertTrue(stopped)
    }

    @Test
    fun usableBetweenTheFirstCheckAndRegistration() {
        var usable = false
        val waiter = NetworkWaiter(
            isUsable = { usable },
            observe = { _ ->
                usable = true // the network came up while the watcher was being set up
                { }
            },
        )
        // No change is ever reported, so only the second up-front check can end this well.
        val result = runBlocking { waiter.awaitUsable(100) }
        assertTrue(result)
    }

    @Test
    fun timesOutWhileNoNetworkEverBecomesUsable() {
        var stopped = false
        val waiter = NetworkWaiter(
            isUsable = { false },
            observe = { onChange ->
                val stop: () -> Unit = { stopped = true }
                Thread {
                    Thread.sleep(50)
                    onChange() // a change that still leaves no usable network
                }.start()
                stop
            },
        )
        // A change must not end the wait on its own: completing on it would return true.
        val result = runBlocking { waiter.awaitUsable(300) }
        assertFalse(result)
        assertTrue(stopped)
    }
}