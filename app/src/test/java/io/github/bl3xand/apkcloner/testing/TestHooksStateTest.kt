package io.github.bl3xand.apkcloner.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The Android-free state behind TestHooks: the ring of notifications, the install
// bookkeeping and the started/finished counting of background checks, tested as is.
// The gate itself (enabled) is what keeps release builds empty, and the instrumentation
// tests cover it in the app's own debug process.

class TestHooksStateTest {

    private val state = TestHooks.State()

    @Test
    fun notificationsKeepTheLastEntriesOnly() {
        repeat(TestHooks.NOTIFICATION_RING_SIZE + 10) { i ->
            state.recordNotification(TestHooks.NotificationRecord("sources_BG_UPDATE_CHECK", 1000 + i, "title $i"))
        }
        val kept = state.notifications
        assertEquals(TestHooks.NOTIFICATION_RING_SIZE, kept.size)
        // The oldest of the kept is the one right after the dropped ones.
        assertEquals("title 10", kept.first().title)
        // The newest is the last recorded.
        assertEquals("title ${TestHooks.NOTIFICATION_RING_SIZE + 9}", kept.last().title)
    }

    @Test
    fun installStartedAndFinishedMoveTheCounterTogether() {
        state.recordInstallStarted("com.example.one")
        state.recordInstallStarted("com.example.two")
        assertEquals(setOf("com.example.one", "com.example.two"), state.currentInstalls)
        assertEquals(0, state.finishedInstallCount)
        state.recordInstallFinished("com.example.one")
        assertEquals(setOf("com.example.two"), state.currentInstalls)
        assertEquals(1, state.finishedInstallCount)
        state.recordInstallFinished("com.example.two")
        assertEquals(emptySet<String>(), state.currentInstalls)
        assertEquals(2, state.finishedInstallCount)
    }

    @Test
    fun aFinishWithoutAStartCountsAsNothing() {
        state.recordInstallFinished("com.example.never")
        assertEquals(emptySet<String>(), state.currentInstalls)
        assertEquals(0, state.finishedInstallCount)
    }

    @Test
    fun theSamePackageInstallingTwiceCountsOneFinish() {
        state.recordInstallStarted("com.example.one")
        state.recordInstallStarted("com.example.one")
        state.recordInstallFinished("com.example.one")
        assertEquals(emptySet<String>(), state.currentInstalls)
        assertEquals(1, state.finishedInstallCount)
    }

    @Test
    fun backgroundChecksCountStartsAndFinishesSeparately() {
        state.recordBackgroundCheckStarted()
        state.recordBackgroundCheckStarted()
        state.recordBackgroundCheckFinished()
        assertEquals(2, state.backgroundCheckRunsStarted)
        assertEquals(1, state.backgroundCheckRunsFinished)
        assertTrue(state.lastBackgroundCheckFinishedAt > 0L)
    }

    @Test
    fun resetEmptiesEverything() {
        state.recordNotification(TestHooks.NotificationRecord("updates", 2, "available"))
        state.recordInstallStarted("com.example.one")
        state.recordInstallFinished("com.example.one")
        state.recordBackgroundCheckStarted()
        state.recordBackgroundCheckFinished()
        state.reset()
        assertEquals(emptyList<TestHooks.NotificationRecord>(), state.notifications)
        assertEquals(emptySet<String>(), state.currentInstalls)
        assertEquals(0, state.finishedInstallCount)
        assertEquals(0, state.backgroundCheckRunsStarted)
        assertEquals(0, state.backgroundCheckRunsFinished)
        assertEquals(0L, state.lastBackgroundCheckFinishedAt)
    }

    @Test
    fun theGateKeepsEverythingInvisibleUntilEnabled() {
        // The recording side of the gate: calls before enabling do nothing.
        TestHooks.notificationPosted("sources_UPDATES_AVAILABLE", 1002, "hidden")
        TestHooks.installStarted("com.example.gated")
        TestHooks.installFinished("com.example.gated")
        TestHooks.backgroundCheckStarted()
        TestHooks.backgroundCheckFinished()
        assertEquals(emptyList<TestHooks.NotificationRecord>(), TestHooks.notifications)
        assertEquals(emptySet<String>(), TestHooks.currentInstalls)
        assertEquals(0, TestHooks.finishedInstallCount)
        assertEquals(0, TestHooks.backgroundCheckRunsStarted)
        assertEquals(0L, TestHooks.lastBackgroundCheckFinishedAt)
        // Enabling with a false debuggable flag - what a release build would say - stays off.
        TestHooks.enable(false)
        assertEquals(false, TestHooks.enabled)
    }
}