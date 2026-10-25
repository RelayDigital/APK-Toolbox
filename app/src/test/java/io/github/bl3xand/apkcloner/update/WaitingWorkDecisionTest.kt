package io.github.bl3xand.apkcloner.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The decision behind the waiting work's re-queue, not the WorkManager wiring around it
// (nothing of JobScheduler runs here): a charge-limited battery plugged in at its limit
// reports plugged - what the charging constraint is satisfied by - while the battery itself
// says not charging, and the re-queues that state used to allow were the install storm of
// home-bl1. The reads of the two signals and the enqueue are wiring around this.

class WaitingWorkDecisionTest {

    private val now = System.currentTimeMillis()
    private val paced = AutoUpdateWorker.SIGNAL_MISMATCH_REQUEUE_MS

    @Test
    fun agreeingSignalsRequeueAtOnce() {
        // On battery, or really charging: the constraint alone holds the work, and an
        // immediate re-queue is what makes it fire the moment the state turns good.
        assertEquals(true to 0L, AutoUpdateWorker.shouldRequeueWaitingWork(false, 0, 0, now))
        assertEquals(true to 0L, AutoUpdateWorker.shouldRequeueWaitingWork(false, 0, now, now))
        assertEquals(true to 0L, AutoUpdateWorker.shouldRequeueWaitingWork(true, BatteryPlugged.AC, now, now))
    }

    @Test
    fun chargingStartsRequeueAtOnce() {
        // The storm state turned into a real charge: no pacing may stand between the
        // update and its install.
        assertEquals(true to 0L, AutoUpdateWorker.shouldRequeueWaitingWork(true, BatteryPlugged.USB, now, now))
    }

    @Test
    fun pluggedButNotChargingQueuesPacedWhenNothingStands() {
        // The storm case (a Pixel held at 80%, plugged in, NOT_CHARGING): the constraint is
        // satisfied, so an immediate re-queue would run again at once - and again, and
        // again. Whatever asks with nothing standing gets a paced re-queue instead - a
        // waiting run at its own end (the wiring passes lastEnqueuedAt = 0 there), or a
        // pass that finds no timestamp on record.
        assertEquals(true to paced, AutoUpdateWorker.shouldRequeueWaitingWork(false, BatteryPlugged.USB, 0, now))
        assertEquals(true to paced, AutoUpdateWorker.shouldRequeueWaitingWork(false, BatteryPlugged.AC, 0, now - paced))
    }

    @Test
    fun pluggedButNotChargingLeavesFreshStandingWorkAlone() {
        // A scheduled or manual pass while paced work already stands: replacing it would
        // only restart its delay. A minute-old re-queue is fresh under any sane window.
        val decision = AutoUpdateWorker.shouldRequeueWaitingWork(false, BatteryPlugged.USB, now - 60_000L, now)
        assertFalse(decision.first)
    }

    @Test
    fun pluggedButNotChargingRequeuesStaleStandingWork() {
        // The periodic fallback: standing work a day old is stale, so the pass queues a
        // paced successor and the deferral stays alive however long the state lasts.
        val decision = AutoUpdateWorker.shouldRequeueWaitingWork(false, BatteryPlugged.AC, now - 24 * 3_600_000L, now)
        assertEquals(true to paced, decision)
    }

    @Test
    fun onlyAPluggedBatteryThatIsNotChargingDisagrees() {
        // Unplugged is honest (the constraint really is unsatisfied), and so is charging.
        assertFalse(AutoUpdateWorker.chargingSignalsDisagree(0, false))
        assertFalse(AutoUpdateWorker.chargingSignalsDisagree(BatteryPlugged.USB, true))
        assertFalse(AutoUpdateWorker.chargingSignalsDisagree(BatteryPlugged.WIRELESS, true))
        assertTrue(AutoUpdateWorker.chargingSignalsDisagree(BatteryPlugged.AC, false))
    }

    /** The plugged values EXTRA_PLUGGED carries; only "not zero" matters to the decision. */
    private object BatteryPlugged {
        const val AC = 2
        const val USB = 1
        const val WIRELESS = 4
    }
}