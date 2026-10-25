package io.github.bl3xand.apkcloner.install

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The judgement behind a background session's end: what counts as a failed update and
// what that failure is blamed on. The rest of the receiver - what the broadcast itself
// does with this - is wiring around it, and is what InstallReceiverSessionTest covers
// for the per-session half.

class InstallBackgroundResultTest {

    private val confirmNeeded = "the system asked for confirmation"

    @Test
    fun successIsNotAFailure() {
        assertNull(InstallReceiver.backgroundFailureReason(PackageInstaller.STATUS_SUCCESS, null, confirmNeeded))
        // Even with stray words attached, the system said it worked.
        assertNull(InstallReceiver.backgroundFailureReason(PackageInstaller.STATUS_SUCCESS, "whatever", confirmNeeded))
    }

    @Test
    fun aConfirmationThisProcessCannotAnswerIsAFailure() {
        // The case that used to vanish: the process that started the install was gone, so
        // nobody waited for this result - and a dropped ask was reported as nothing at all.
        assertEquals(confirmNeeded, InstallReceiver.backgroundFailureReason(PackageInstaller.STATUS_PENDING_USER_ACTION, null, confirmNeeded))
    }

    @Test
    fun aFailureCarriesTheSystemsWords() {
        assertEquals("INSTALL_FAILED_BLOCKED", InstallReceiver.backgroundFailureReason(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_BLOCKED", confirmNeeded))
    }

    @Test
    fun aWordlessFailureFallsBackToItsStatusNumber() {
        assertEquals("status 3", InstallReceiver.backgroundFailureReason(3, " ", confirmNeeded))
    }
}