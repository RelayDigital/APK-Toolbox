package io.github.bl3xand.apkcloner.ui

import io.github.bl3xand.apkcloner.clone.PatchIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's one dependency: mimicry includes the signature substitution, so the two are
 * picked together. [patchToggled] is the whole rule, tested as the pure function it is - what
 * one flip of a switch does to the set, and whose switches must be seen to follow.
 */
class PatchToggledTest {

    @Test
    fun checkingMimicryBringsTheSubstitutionAlong() {
        val (set, followed) = patchToggled(emptySet(), PatchIds.MIMICRY, on = true)
        assertEquals(setOf(PatchIds.MIMICRY, PatchIds.SPOOF_SIGNATURE), set)
        assertEquals("the companion is seen to turn on", setOf(PatchIds.SPOOF_SIGNATURE), followed)
    }

    @Test
    fun theSubstitutionStaysOnWhileMimicryIsOn() {
        // Switching the substitution off under mimicry: it comes back, visibly.
        val (kept, followedBack) = patchToggled(setOf(PatchIds.MIMICRY, PatchIds.SPOOF_SIGNATURE), PatchIds.SPOOF_SIGNATURE, on = false)
        assertEquals(setOf(PatchIds.MIMICRY, PatchIds.SPOOF_SIGNATURE), kept)
        assertEquals(setOf(PatchIds.SPOOF_SIGNATURE), followedBack)

        // With mimicry gone first, the substitution goes off like any other patch.
        val (released, nothing) = patchToggled(setOf(PatchIds.SPOOF_SIGNATURE), PatchIds.SPOOF_SIGNATURE, on = false)
        assertEquals(emptySet<String>(), released)
        assertTrue(nothing.isEmpty())
    }

    @Test
    fun theSubstitutionAloneIsAValidChoice() {
        val (set, followed) = patchToggled(emptySet(), PatchIds.SPOOF_SIGNATURE, on = true)
        assertEquals(setOf(PatchIds.SPOOF_SIGNATURE), set)
        assertTrue(followed.isEmpty())
    }

    @Test
    fun otherPatchesDoNotTouchThePair() {
        val (set, followed) = patchToggled(setOf(PatchIds.MIMICRY, PatchIds.SPOOF_SIGNATURE), PatchIds.KEEP_ALIVE, on = true)
        assertEquals(setOf(PatchIds.MIMICRY, PatchIds.SPOOF_SIGNATURE, PatchIds.KEEP_ALIVE), set)
        assertTrue(followed.isEmpty())
    }

    @Test
    fun uncheckingMimicryLeavesTheSubstitutionWhereItWas() {
        // Mimicry off does not drag the companion off: the lighter patch is still useful.
        val (set, followed) = patchToggled(setOf(PatchIds.MIMICRY, PatchIds.SPOOF_SIGNATURE), PatchIds.MIMICRY, on = false)
        assertEquals(setOf(PatchIds.SPOOF_SIGNATURE), set)
        assertTrue(followed.isEmpty())
    }
}