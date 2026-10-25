package io.github.bl3xand.apkcloner.ui.clone

import io.github.bl3xand.apkcloner.clone.PatchIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's toggling, as the pure function it is ([patchToggled]): what one flip of a switch
 * does to the set. The picker offers one switch per mechanism (exit suppression, the alias,
 * mimicry) - nothing follows from anything any more, and the ids a stored set may hold that the
 * picker offers no row for (an older spoofSignature, a legacy bypass) ride along untouched.
 */
class PatchToggledTest {

    @Test
    fun aFlipAddsOrRemovesJustItsPatch() {
        val (on, followed) = patchToggled(emptySet(), PatchIds.MIMICRY, on = true)
        assertEquals(setOf(PatchIds.MIMICRY), on)
        assertTrue(followed.isEmpty())

        val (off, stillNothing) = patchToggled(setOf(PatchIds.MIMICRY), PatchIds.MIMICRY, on = false)
        assertEquals(emptySet<String>(), off)
        assertTrue(stillNothing.isEmpty())
    }

    @Test
    fun aStoredSubstitutionRidesAlongUntouched() {
        // A set from before the split: the substitution has no row of its own any more, so
        // flipping any offered switch must not drop it from the set.
        val legacy = setOf(PatchIds.KEEP_ALIVE, PatchIds.SPOOF_SIGNATURE)
        val (withAlias, _) = patchToggled(legacy, PatchIds.RENAME_ALIAS, on = true)
        assertEquals(setOf(PatchIds.KEEP_ALIVE, PatchIds.SPOOF_SIGNATURE, PatchIds.RENAME_ALIAS), withAlias)

        val (mimicryOn, _) = patchToggled(legacy, PatchIds.MIMICRY, on = true)
        // The substitution stays in the set it was already in - mimicry does not need to add
        // it (the build reads the certificate for either), and does not take it away.
        assertEquals(setOf(PatchIds.KEEP_ALIVE, PatchIds.SPOOF_SIGNATURE, PatchIds.MIMICRY), mimicryOn)

        val (keepAliveOff, _) = patchToggled(legacy, PatchIds.KEEP_ALIVE, on = false)
        assertEquals(setOf(PatchIds.SPOOF_SIGNATURE), keepAliveOff)
    }

    @Test
    fun aLegacyBypassRidesAlongToo() {
        val (set, _) = patchToggled(setOf(PatchIds.BYPASS), PatchIds.MIMICRY, on = true)
        assertEquals(setOf(PatchIds.BYPASS, PatchIds.MIMICRY), set)
    }
}