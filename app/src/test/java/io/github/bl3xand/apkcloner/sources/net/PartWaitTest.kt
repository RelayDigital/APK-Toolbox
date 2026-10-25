package io.github.bl3xand.apkcloner.sources.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wait a second download of the same file goes through: the holder is never declared
 * dead while its file still grows, a stall is measured above the read timeout, and the
 * caller's cancellation is honoured inside the wait. The clock and the sleeping are faked,
 * so the whole multi-minute wait runs in microseconds.
 */
class PartWaitTest {
    private class FakeClock {
        var nowMs = 0L
        fun advance(ms: Long) { nowMs += ms }
        val sleeps = mutableListOf<Long>()
    }

    private fun partWait(clock: FakeClock, maxWaitMs: Long = 300_000L, stallMs: Long = 70_000L): PartWait =
        PartWait(
            now = { clock.nowMs },
            sleeper = { clock.sleeps.add(it); clock.advance(it) },
            pollMs = 7_000L,
            stallMs = stallMs,
            maxWaitMs = maxWaitMs,
        )

    @Test
    fun acquiresWhenLockIsFree() {
        val clock = FakeClock()
        val outcome = partWait(clock).await(
            tryAcquire = { true },
            cancelled = { false },
            partSize = { 100L },
        )
        assertEquals(PartWait.Outcome.ACQUIRED, outcome)
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun cancelledWhileWaiting() {
        val clock = FakeClock()
        var polls = 0
        val outcome = partWait(clock).await(
            tryAcquire = { false },
            cancelled = { polls >= 3 },
            partSize = { polls++; null },
        )
        assertEquals(PartWait.Outcome.CANCELLED, outcome)
        // The baseline probe runs once before the loop, then one probe per poll: the
        // cancellation is noticed at the third poll - seconds, not the 5-minute cap.
        assertEquals(listOf(7_000L, 7_000L), clock.sleeps)
    }

    @Test
    fun aGrowingFileIsNeverDeclaredDead() {
        val clock = FakeClock()
        var size = 0L
        var polls = 0
        val outcome = partWait(clock).await(
            tryAcquire = { false },
            // The holder never gives the lock; only the caller's own cancellation ends this,
            // and only well past the max wait - the growth keeps the holder alive past it.
            cancelled = { polls > 45 },
            partSize = {
                polls++
                size += 1_000
                size
            },
        )
        assertEquals(PartWait.Outcome.CANCELLED, outcome)
        // Past the 5-minute max wait: a growing file means a live writer, however slow.
        assertTrue(clock.nowMs > 300_000L)
    }

    @Test
    fun wedgedHolderTimesOutAtTheMaxWait() {
        val clock = FakeClock()
        val outcome = partWait(clock).await(
            tryAcquire = { false },
            cancelled = { false },
            partSize = { 500L }, // exists, never grows
        )
        assertEquals(PartWait.Outcome.TIMED_OUT, outcome)
        // A wedged holder is given the whole max wait - far more than the old code's single
        // 7-second stall - before the conflict is reported.
        assertTrue(clock.nowMs >= 300_000L)
    }

    @Test
    fun aStallIsOnlyFatalPastTheStallThreshold() {
        val clock = FakeClock()
        var size = 0L
        var polls = 0
        val outcome = partWait(clock, maxWaitMs = 50_000L, stallMs = 70_000L).await(
            tryAcquire = { false },
            cancelled = { false },
            partSize = {
                polls++
                // Grows for the first two probes (30 s apart), then goes silent.
                if (polls <= 2) {
                    clock.advance(30_000)
                    size += 1_000
                }
                size
            },
        )
        assertEquals(PartWait.Outcome.TIMED_OUT, outcome)
        // The last growth landed at t=60 s; death is declared only past the stall threshold
        // above it (the read timeout is 60 s, so a healthy blocked read survives this).
        assertTrue("declared dead at ${clock.nowMs} ms", clock.nowMs >= 60_000L + 70_000L)
    }

    @Test
    fun aStalledFileThatStartsGrowingAgainIsNotDead() {
        val clock = FakeClock()
        var size = 0L
        var polls = 0
        val outcome = partWait(clock, maxWaitMs = 50_000L, stallMs = 70_000L).await(
            tryAcquire = { polls >= 12 }, // long after an early stall would have given up
            cancelled = { false },
            partSize = {
                polls++
                // Stalled silent past the max wait, then the writer recovers and grows again.
                if (polls > 6) size += 1_000
                size
            },
        )
        assertEquals(PartWait.Outcome.ACQUIRED, outcome)
    }

    @Test
    fun ownershipWonAfterALongStallIsMarkedSuspect() {
        val clock = FakeClock()
        var polls = 0
        val outcome = partWait(clock).await(
            tryAcquire = { polls >= 40 },
            cancelled = { false },
            partSize = { polls++; 500L }, // never grows: the holder went silent
        )
        assertEquals(PartWait.Outcome.ACQUIRED_AFTER_STALL, outcome)
    }

    @Test
    fun thresholdDefaultsSitAboveTheReadTimeout() {
        // Http's read timeout is 60s: a stall must be longer than that to mean anything.
        assertTrue(PartWait.STALL_MS > 60_000L)
        assertTrue(PartWait.MAX_WAIT_MS > PartWait.STALL_MS)
    }
}

/** The in-process write-ownership registry behind the wait. */
class PartFileLocksTest {
    @Test
    fun secondThreadCannotTakeAHeldLock() {
        val path = "/tmp/does-not-matter-${System.nanoTime()}.part"
        assertTrue(PartFileLocks.tryAcquire(path))
        val fromOther = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread { fromOther.set(PartFileLocks.tryAcquire(path)) }
        thread.start()
        thread.join()
        assertFalse(fromOther.get())
        assertTrue(PartFileLocks.isHeld(path))
        PartFileLocks.release(path)
        // With the holder gone, the next taker wins and the registry entry is reused.
        assertTrue(PartFileLocks.tryAcquire(path))
        PartFileLocks.release(path)
    }

    @Test
    fun releaseByANonOwnerIsIgnored() {
        val path = "/tmp/does-not-matter-2-${System.nanoTime()}.part"
        assertTrue(PartFileLocks.tryAcquire(path))
        val thread = Thread { PartFileLocks.release(path) } // must not throw
        thread.start()
        thread.join()
        assertTrue(PartFileLocks.isHeld(path)) // still ours
        PartFileLocks.release(path)
        assertFalse(PartFileLocks.isHeld(path))
    }
}