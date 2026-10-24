package io.github.bl3xand.apkcloner.install

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The locking rules of the fix for updates interrupting each other: InstallCoordinator is
// plain Kotlin, so its guarantees are tested here directly, without any Android wiring.
// An install asked for on screen is foreground here (background = false); a worker pass
// wraps itself in backgroundBatch and installs with background = true.

class InstallCoordinatorTest {

    @Test
    fun samePackageNeverInstallsTwiceAtOnce() = runBlocking {
        val inFirst = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var inside = 0
        val first = launch {
            InstallCoordinator.installing("com.example.one", background = false) {
                inside++
                inFirst.complete(Unit)
                releaseFirst.await()
                inside--
            }
        }
        inFirst.await()
        val second = launch {
            InstallCoordinator.installing("com.example.one", background = false) {
                inside++
                inside--
            }
        }
        // Plenty of turns for the second to (wrongly) slip in; it must stay queued.
        delay(100)
        assertTrue(second.isActive)
        assertEquals(1, inside)
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertEquals(0, inside)
    }

    @Test
    fun differentPackagesInstallConcurrentlyInsideOneBatch() = runBlocking {
        val one = CompletableDeferred<Unit>()
        val two = CompletableDeferred<Unit>()
        val batch = InstallCoordinator.backgroundBatch {
            val first = launch {
                InstallCoordinator.installing("com.example.one", background = true) {
                    one.complete(Unit)
                    two.await() // only ends when the other package's install is running too
                }
            }
            val second = launch {
                InstallCoordinator.installing("com.example.two", background = true) {
                    two.complete(Unit)
                    one.await()
                }
            }
            first.join()
            second.join()
        }
        withTimeout(5_000) { batch }
    }

    @Test
    fun foregroundCommitsAreOneAtATime() = runBlocking {
        var inside = 0
        var maxInside = 0
        val jobs = (0 until 4).map { n ->
            launch {
                InstallCoordinator.installing("com.example.$n", background = false) {
                    commit {
                        inside++
                        maxInside = maxOf(maxInside, inside)
                        delay(50)
                        inside--
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(1, maxInside)
    }

    @Test
    fun aLeftOpenForegroundPromptHoldsUpTheNextForegroundInstall() = runBlocking {
        val firstCommitted = CompletableDeferred<Unit>()
        val firstAnswered = CompletableDeferred<Unit>()
        val secondCommitted = CompletableDeferred<Unit>()
        // The first package commits and then waits minutes for the user to answer; the
        // second foreground install must not stack its own system prompt on top of that.
        val first = launch {
            InstallCoordinator.installing("com.example.one", background = false) {
                commit { firstCommitted.complete(Unit) }
                firstAnswered.await()
            }
        }
        firstCommitted.await()
        val second = launch {
            InstallCoordinator.installing("com.example.two", background = false) {
                commit { secondCommitted.complete(Unit) }
            }
        }
        delay(100)
        assertTrue(second.isActive)
        assertTrue(!secondCommitted.isCompleted)
        firstAnswered.complete(Unit)
        withTimeout(1_000) { secondCommitted.await() }
        withTimeout(1_000) {
            first.join()
            second.join()
        }
    }

    @Test
    fun secondCommitWaitsWhileTheFirstHoldsTheQueue() = runBlocking {
        val firstInCommit = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val secondInCommit = CompletableDeferred<Unit>()
        launch {
            InstallCoordinator.installing("com.example.one", background = false) {
                commit {
                    firstInCommit.complete(Unit)
                    releaseCommit.await()
                }
            }
        }
        firstInCommit.await()
        val second = launch {
            InstallCoordinator.installing("com.example.two", background = false) {
                commit { secondInCommit.complete(Unit) }
            }
        }
        delay(100)
        // The queue is held by the first; the second must still be waiting.
        assertTrue(second.isActive)
        releaseCommit.complete(Unit)
        withTimeout(1_000) { secondInCommit.await() }
    }

    @Test
    fun foregroundWaitsOutARunningBackgroundBatch() = runBlocking {
        val batchStarted = CompletableDeferred<Unit>()
        val releaseBatch = CompletableDeferred<Unit>()
        var foregroundRan = false
        val batch = launch {
            InstallCoordinator.backgroundBatch {
                InstallCoordinator.installing("com.example.one", background = true) {
                    batchStarted.complete(Unit)
                    releaseBatch.await()
                }
            }
        }
        batchStarted.await()
        val foreground = launch {
            // A different package, so only the batch can be what it waits for.
            InstallCoordinator.installing("com.example.two", background = false) {
                commit { foregroundRan = true }
            }
        }
        delay(100)
        assertTrue(foreground.isActive)
        assertTrue(!foregroundRan)
        releaseBatch.complete(Unit)
        withTimeout(1_000) {
            batch.join()
            foreground.join()
        }
        assertTrue(foregroundRan)
    }

    @Test
    fun aForegroundInstallAfterABatchWaitsForTheBatchToFinishCompletely() = runBlocking {
        val batchCommitted = CompletableDeferred<Unit>()
        val releaseBatch = CompletableDeferred<Unit>()
        val foregroundCommitted = CompletableDeferred<Unit>()
        val batch = launch {
            InstallCoordinator.backgroundBatch {
                InstallCoordinator.installing("com.example.one", background = true) {
                    commit { batchCommitted.complete(Unit) }
                    // The batch is still waiting for the system's answer, as it would for
                    // a slow silent install; the whole batch is in its install phase.
                    releaseBatch.await()
                }
            }
        }
        batchCommitted.await()
        val foreground = launch {
            // A different package, so only the batch's install phase can be what it waits for.
            InstallCoordinator.installing("com.example.two", background = false) {
                commit { foregroundCommitted.complete(Unit) }
            }
        }
        delay(100)
        // A foreground obtain does not interleave into the middle of a background batch,
        // answer waits included.
        assertTrue(foreground.isActive)
        assertTrue(!foregroundCommitted.isCompleted)
        releaseBatch.complete(Unit)
        withTimeout(1_000) {
            foregroundCommitted.await()
            batch.join()
            foreground.join()
        }
    }

    @Test
    fun countsInFlightInstalls() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val leave = CompletableDeferred<Unit>()
        assertEquals(0, InstallCoordinator.installs.value)
        val job = launch {
            InstallCoordinator.installing("com.example.one", background = false) {
                entered.complete(Unit)
                leave.await()
            }
        }
        entered.await()
        assertEquals(1, InstallCoordinator.installs.value)
        leave.complete(Unit)
        job.join()
        assertEquals(0, InstallCoordinator.installs.value)
    }

    @Test
    fun releasesThePackageWhenTheInstallThrows() = runBlocking {
        runCatching {
            InstallCoordinator.installing("com.example.one", background = false) {
                throw IllegalStateException("boom")
            }
        }
        // The package lock must be free again: a later install of the same package runs.
        withTimeout(1_000) {
            InstallCoordinator.installing("com.example.one", background = false) { }
        }
        assertEquals(0, InstallCoordinator.installs.value)
    }

    @Test
    fun installsRunInTheOrderTheyArrive() = runBlocking {
        val order = ArrayList<Int>()
        // All under one package: the per-package mutex is a fair queue, and the single
        // thread of runBlocking starts the coroutines in creation order, so the installs
        // must finish in exactly the order they were asked for.
        val jobs = (0 until 5).map { n ->
            async {
                InstallCoordinator.installing("com.example.serial", background = false) {
                    commit { synchronized(order) { order.add(n) } }
                }
            }
        }
        jobs.forEach { it.await() }
        assertEquals((0 until 5).toList(), order)
    }
}