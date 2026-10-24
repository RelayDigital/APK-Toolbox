package io.github.bl3xand.apkcloner.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// How a root install is judged: `pm install` exits 0 even when it refused the package,
// so the exit code alone would count a failed install as done - and a background update
// pass would say the app "was updated" while nothing changed on the device. The words pm
// printed decide, with the code as the tiebreaker. What runs su itself is wiring around
// this.

class RootInstallJudgementTest {

    @Test
    fun successIsStillSuccess() {
        assertNull(Root.installFailureOf(0, "Success\n"))
    }

    @Test
    fun aRefusalWithExitZeroIsAFailure() {
        // The bug this guards: pm prints its refusal and still exits 0.
        assertEquals(
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]",
            Root.installFailureOf(0, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"),
        )
    }

    @Test
    fun anErrorLineWithExitZeroIsAFailure() {
        assertEquals("Error: cannot read path", Root.installFailureOf(0, "Error: cannot read path"))
    }

    @Test
    fun aNonzeroExitSaysSoWhenPmSaidNothing() {
        assertEquals("pm install failed (1)", Root.installFailureOf(1, ""))
    }

    @Test
    fun aRefusalAmongOtherLinesIsStillSeen() {
        // The script runs cp and am before pm; their output can share the pipe.
        assertNull(Root.installFailureOf(0, "\n\nSuccess"))
        assertEquals(
            "before\nFailure [INSTALL_FAILED_OLDER_SDK]\nafter",
            Root.installFailureOf(0, "before\nFailure [INSTALL_FAILED_OLDER_SDK]\nafter"),
        )
    }
}