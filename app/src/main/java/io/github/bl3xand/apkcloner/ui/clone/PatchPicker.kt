package io.github.bl3xand.apkcloner.ui.clone

import android.content.Context
import com.google.android.material.R as MaterialR
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.clone.dex.PatchIds
import io.github.bl3xand.apkcloner.ui.Spacing
import io.github.bl3xand.apkcloner.ui.add
import io.github.bl3xand.apkcloner.ui.column
import io.github.bl3xand.apkcloner.ui.label
import io.github.bl3xand.apkcloner.ui.showSheet
import io.github.bl3xand.apkcloner.ui.switchRow

/** The patches a clone can be built with: a stable [id] for the metadata, and UI text. */
data class ClonePatch(
    val id: String,
    val label: String,
    val note: String,
    /** The switch cannot move while another patch implies this one; the row says whose it is. */
    val impliedBy: String? = null,
)

/**
 * What one flip of a patch's switch does to the set. The only rules are the lattice's own:
 * mimicry includes the signature substitution - switched on, it takes the substitution with it
 * and the substitution's own row is held on by it; switched off, the substitution falls back to
 * whatever the user leaves it at. Nothing else follows from anything: the ids a stored set may
 * hold that the picker offers no row for (an older set, a legacy bypass) ride along untouched,
 * and no combination is rewritten into another, so a set read by two readers at once cannot
 * disagree about what it says.
 *
 * Returns the next set and the ids whose rows must be shown as held (the substitution under
 * mimicry): a purely derived view of the same set, never a second source of truth.
 */
fun patchToggled(set: Set<String>, id: String, on: Boolean): Pair<Set<String>, Set<String>> {
    val current = set.toMutableSet()
    if (on) {
        current.add(id)
        if (id == PatchIds.MIMICRY) current.add(PatchIds.SPOOF_SIGNATURE)
    } else {
        current.remove(id)
    }
    val held = if (PatchIds.MIMICRY in current && PatchIds.SPOOF_SIGNATURE in current) {
        setOf(PatchIds.SPOOF_SIGNATURE)
    } else {
        emptySet()
    }
    return current.toSet() to held
}

/**
 * Lets the user pick the patches a clone is built with, in a sheet of its own - the same pattern
 * as the permissions picker. Every patch is opt-in; [onDone] gets the chosen patch ids once the
 * choice is confirmed. A stored set may hold ids the picker no longer offers a row for - they
 * ride along untouched, and keep building as they always did.
 */
fun Context.pickClonePatches(
    patches: List<ClonePatch>,
    chosen: Set<String>,
    onCancel: (() -> Unit)? = null,
    onDone: (Set<String>) -> Unit,
) {
    val current = chosen.toMutableSet()
    val held = mutableSetOf<String>()
    fun deriveHeld() {
        held.clear()
        // The rows a patch holds on arrive derived from the set, the same way every later
        // re-render derives them: one rule, one place.
        if (PatchIds.MIMICRY in current && PatchIds.SPOOF_SIGNATURE in current) held.add(PatchIds.SPOOF_SIGNATURE)
    }
    deriveHeld()
    val list = column(Spacing.SHEET)
    list.add(label(getString(R.string.clone_patches_hint), colorAttr = MaterialR.attr.colorOnSurfaceVariant))
    // The rows are built once; what a flip changes is read back into them, switch by switch,
    // so the sheet always shows the set as the one rule derives it - held rows included.
    val switches = HashMap<String, MaterialSwitch>()
    for (patch in patches) {
        val row = switchRow(patch.label, patch.id in current, patch.note, enabled = patch.impliedBy == null || patch.id !in held) { checked ->
            val (next, nowHeld) = patchToggled(current, patch.id, checked)
            current.clear()
            current.addAll(next)
            held.clear()
            held.addAll(nowHeld)
            patches.forEach { p ->
                switches[p.id]?.isChecked = p.id in current
                switches[p.id]?.isEnabled = p.impliedBy == null || p.id !in held
            }
        }
        (row.getChildAt(row.childCount - 1) as? MaterialSwitch)?.let { switches[patch.id] = it }
        list.add(row)
    }
    var done = false
    showSheet(
        getString(R.string.clone_patches), content = list,
        positive = getString(R.string.button_done), negative = getString(android.R.string.cancel),
        onDismiss = { if (!done) onCancel?.invoke() },
    ) {
        done = true
        onDone(current.toSet())
        true
    }
}

/** The label of the button that opens the picker: how many patches are on, if any. */
fun Context.clonePatchesLabel(on: Int, total: Int): String =
    if (on == 0) getString(R.string.clone_patches) else getString(R.string.clone_patches_on, on, total)

/**
 * Every patch the picker offers, in the order they are offered in. Four switches: exit
 * suppression, the package-name alias, the signature substitution, and mimicry - the full
 * identity, which includes the substitution (a stored spoofSignature keeps building as it
 * always did; under mimicry its row is held on by mimicry and cannot move).
 */
fun Context.clonePatches(): List<ClonePatch> = listOf(
    // PatchIds.BYPASS is kept for the metadata of clones made before this split; it is not
    // offered as a row - BYPASS is KEEP_ALIVE's subset.
    ClonePatch(
        id = PatchIds.KEEP_ALIVE,
        label = getString(R.string.clone_keep_alive),
        note = getString(R.string.clone_keep_alive_note),
    ),
    ClonePatch(
        id = PatchIds.RENAME_ALIAS,
        label = getString(R.string.clone_rename_alias),
        note = getString(R.string.clone_rename_alias_note),
    ),
    ClonePatch(
        id = PatchIds.SPOOF_SIGNATURE,
        label = getString(R.string.clone_spoof_signature),
        note = getString(R.string.clone_spoof_signature_note),
        impliedBy = PatchIds.MIMICRY,
    ),
    ClonePatch(
        id = PatchIds.MIMICRY,
        label = getString(R.string.clone_mimicry),
        note = getString(R.string.clone_mimicry_note),
    ),
)