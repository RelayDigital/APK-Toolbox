package io.github.bl3xand.apkcloner.ui.clone

import io.github.bl3xand.apkcloner.clone.dex.PatchIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's toggling, as the pure function it is ([patchToggled]): what one flip of a switch
 * does to the set, and the lattice's one rule - mimicry includes the signature substitution.
 *
 * The reachability claims here are the regression the split exists for: the set that the working
 * Xiaomi clone was verified with (17.10, keepAlive + the substitution alone) must be reachable
 * again, whatever order the switches are flipped in, and no sequence of flips may leave the set
 * in a state the build reads differently than the picker shows.
 */
class PatchToggledTest {

    private val ka = PatchIds.KEEP_ALIVE
    private val alias = PatchIds.RENAME_ALIAS
    private val spoof = PatchIds.SPOOF_SIGNATURE
    private val mimicry = PatchIds.MIMICRY

    @Test
    fun aFlipAddsOrRemovesJustItsPatch() {
        val (on, held) = patchToggled(emptySet(), mimicry, on = true)
        // Mimicry takes its companion with it - the lattice's one rule - and holds its row.
        assertEquals(setOf(mimicry, spoof), on)
        assertEquals(setOf(spoof), held)

        val (off, _) = patchToggled(setOf(mimicry, spoof), mimicry, on = false)
        // Off leaves the companion where the user had it: the fresh set here had it only
        // through mimicry, yet the rule is the same one that keeps a user's own choice - the
        // companion stays, as one the user can now switch off on its own row.
        assertEquals(setOf(spoof), off)
    }

    @Test
    fun theVerifiedXiaomiSetIsReachableAgain() {
        // The combination the owner logged into Xiaomi Home with, on 17.10: exit suppression
        // and the signature substitution ALONE - no name spoofing. This is the row of the
        // matrix the picker lost when mimicry absorbed the substitution, and the reason the
        // split exists.
        val (withSpoof, held) = patchToggled(setOf(ka), spoof, on = true)
        assertEquals(setOf(ka, spoof), withSpoof)
        assertTrue("nothing holds the substitution when mimicry is off", held.isEmpty())

        // And it survives a round trip through mimicry: turning mimicry on and off must not
        // smuggle the name spoofing into a set that never chose it.
        val (withMimicry, heldUnder) = patchToggled(withSpoof, mimicry, on = true)
        assertEquals(setOf(ka, spoof, mimicry), withMimicry)
        assertEquals("mimicry holds the substitution's row", setOf(spoof), heldUnder)

        val (back, heldAgain) = patchToggled(withMimicry, mimicry, on = false)
        assertEquals("the user's own substitution survives mimicry going off", setOf(ka, spoof), back)
        assertTrue(heldAgain.isEmpty())
    }

    @Test
    fun theSubstitutionRowIsHeldOnlyUnderMimicry() {
        // The held set is a derived view, never a second state: it says spoof exactly when
        // both are in the set, and nothing otherwise.
        val (_, heldWithBoth) = patchToggled(setOf(spoof, mimicry), ka, on = true)
        assertEquals(setOf(spoof), heldWithBoth)

        val (_, heldWithout) = patchToggled(setOf(spoof), ka, on = true)
        assertTrue(heldWithout.isEmpty())
    }

    @Test
    fun flippingMimicryOffKeepsAUserChosenSubstitution() {
        // The user picked the substitution on its own, then tried mimicry, then thought better
        // of it: their own choice stands.
        val set = setOf(ka, spoof)
        val (withMimicry, _) = patchToggled(set, mimicry, on = true)
        val (withoutMimicry, _) = patchToggled(withMimicry, mimicry, on = false)
        assertEquals(setOf(ka, spoof), withoutMimicry)
    }

    @Test
    fun everySetIsReachableFromAnyOtherByFlipsAlone() {
        // No order-dependence and no dead ends: the toggle's effect depends only on
        // (set, id, on), so two pickers shown the same set cannot diverge, and the reachable
        // sets are closed under the rule. Stated exhaustively over the offered ids.
        val ids = listOf(ka, alias, spoof, mimicry)
        val reachable = mutableSetOf(setOf<String>())
        var grew = true
        while (grew) {
            grew = false
            reachable.toList().forEach { set ->
                ids.forEach { id ->
                    listOf(true, false).forEach { on ->
                        val (next, _) = patchToggled(set, id, on)
                        if (reachable.add(next)) grew = true
                    }
                }
            }
        }
        // The sets that matter: every subset of {ka, alias, spoof} (mimicry off), and every
        // such subset plus {mimicry, spoof} (mimicry on - with its companion always present).
        // The closure also holds the four mimicry-without-companion sets: the UI never offers
        // that flip (the companion's row is held), but should one arrive anyway - a stored set
        // from an import, say - the build reads it as the full stack, exactly as it always has,
        // so the state stays consistent instead of becoming a dead end.
        val expected = buildSet {
            subsetsOf(setOf(ka, alias, spoof)).forEach { base ->
                add(base)
                add(base + mimicry + spoof)
                add(base + mimicry)
            }
        }
        assertEquals("the reachable sets are exactly the consistent ones", expected, reachable)
    }

    @Test
    fun aStoredSubstitutionRidesAlongUntouched() {
        // A set from before the split: the substitution has its row back, and flipping any
        // other switch must not drop it.
        val legacy = setOf(ka, spoof)
        val (withAlias, _) = patchToggled(legacy, alias, on = true)
        assertEquals(setOf(ka, spoof, alias), withAlias)

        val (mimicryOn, _) = patchToggled(legacy, mimicry, on = true)
        assertEquals(setOf(ka, spoof, mimicry), mimicryOn)

        val (keepAliveOff, _) = patchToggled(legacy, ka, on = false)
        assertEquals(setOf(spoof), keepAliveOff)
    }

    @Test
    fun aLegacyBypassRidesAlongToo() {
        val (set, _) = patchToggled(setOf(PatchIds.BYPASS), mimicry, on = true)
        assertEquals(setOf(PatchIds.BYPASS, mimicry, spoof), set)
    }

    private fun subsetsOf(set: Set<String>): Set<Set<String>> =
        set.fold(setOf(setOf<String>())) { acc, element -> acc + acc.map { it + element } }
}