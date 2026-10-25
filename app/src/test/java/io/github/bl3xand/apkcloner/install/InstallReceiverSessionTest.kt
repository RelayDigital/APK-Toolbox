package io.github.bl3xand.apkcloner.install

import android.content.pm.PackageInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The per-session half of install results: the receiver's claim bookkeeping and the
// keying of results by session are plain state, tested here without any Android wiring
// (reportForTest emits what the system's broadcast would). What the broadcast itself
// does with a claim - staying silent about a toast - is wiring around this state.

class InstallReceiverSessionTest {

    @Test
    fun aClaimIsConsumedByItsOwnSession() {
        InstallReceiver.claimSelfReport(41)
        assertTrue(InstallReceiver.isSelfReported(41))
        assertFalse(InstallReceiver.isSelfReported(42))
        // Claims for other sessions are left standing by an unrelated result.
        InstallReceiver.reportForTest(42, "com.example.two", PackageInstaller.STATUS_SUCCESS)
        assertTrue(InstallReceiver.isSelfReported(41))
    }

    @Test
    fun aWaiterWaitsForItsOwnSessionOnly() = runBlocking {
        val waiter = async { InstallReceiver.awaitSession(7) }
        delay(50) // let the waiter subscribe
        // Another session ends first; it must not answer this waiter.
        InstallReceiver.reportForTest(8, "com.example.other", PackageInstaller.STATUS_SUCCESS)
        delay(100)
        assertTrue(waiter.isActive)
        InstallReceiver.reportForTest(7, "com.example.one", PackageInstaller.STATUS_SUCCESS)
        val result = withTimeout(1_000) { waiter.await() }
        assertTrue(result.succeeded)
    }

    @Test
    fun twoSessionsEndingTogetherAnswerTheirOwnWaiters() = runBlocking {
        val first = async { InstallReceiver.awaitSession(1) }
        val second = async { InstallReceiver.awaitSession(2) }
        delay(50)
        // End both, in the opposite order to their waiters' starts.
        InstallReceiver.reportForTest(2, "com.example.two", PackageInstaller.STATUS_FAILURE_ABORTED)
        InstallReceiver.reportForTest(1, "com.example.one", PackageInstaller.STATUS_SUCCESS)
        val firstResult = withTimeout(1_000) { first.await() }
        val secondResult = withTimeout(1_000) { second.await() }
        assertTrue(firstResult.succeeded)
        assertFalse(secondResult.succeeded)
    }

    @Test
    fun aLateWaiterSeesARecentResultReplayed() = runBlocking {
        InstallReceiver.reportForTest(9, "com.example.one", PackageInstaller.STATUS_FAILURE, "no space")
        // The result arrived before this waiter existed; the replay must still answer it.
        val result = withTimeout(1_000) { InstallReceiver.awaitSession(9) }
        assertFalse(result.succeeded)
        assertTrue(result.reasonText.contains("no space"))
    }

    @Test
    fun aSilentInstallerNamesItsPackageWithoutASession() = runBlocking {
        // Root/Shizuku installs know no session; their waiters are keyed by NO_SESSION.
        val waiter = async { InstallReceiver.awaitSession(InstallReceiver.NO_SESSION) }
        delay(50)
        InstallReceiver.notifyFinished("com.example.rooted")
        val result = withTimeout(1_000) { waiter.await() }
        assertTrue(result.succeeded)
    }
}