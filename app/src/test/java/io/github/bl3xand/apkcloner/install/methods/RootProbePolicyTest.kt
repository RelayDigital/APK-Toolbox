package io.github.bl3xand.apkcloner.install.methods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The decisions around root, not the su fork itself (no rooted device runs here): how long
// one probe's answer stands, so repeated installs do not re-ask the root manager, least of
// all from the background. The probe that reads the answer off `su` is wiring around this.

class RootProbePolicyTest {

    @Test
    fun aGrantStandsLongerThanADenial() {
        // Root does not go away on its own; a grant can arrive at any moment.
        assertTrue(Root.answerStands(probed = true, ageMs = 4 * 60_000L))
        assertFalse(Root.answerStands(probed = false, ageMs = 4 * 60_000L))
    }

    @Test
    fun eitherAnswerExpires() {
        // Long enough for a whole update pass; short enough that a grant made in between
        // (the settings switch asks the moment it is turned on) is picked up soon after.
        assertFalse(Root.answerStands(probed = true, ageMs = 6 * 60_000L))
        assertFalse(Root.answerStands(probed = false, ageMs = 16_000L))
    }

    @Test
    fun aFreshDenialStands() {
        // The case the cache is for: a background pass installing app after app, each of
        // which would otherwise fork `su -c id -u` and prompt the root manager again.
        assertTrue(Root.answerStands(probed = false, ageMs = 14_000L))
    }
}