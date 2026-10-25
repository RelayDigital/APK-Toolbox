package io.github.bl3xand.apkcloner.ui.clone

import android.content.Context
import com.google.android.material.R as MaterialR
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
)

/**
 * What one flip of a patch's switch does to the set: adds or removes the patch. Nothing follows
 * from anything here any more - the identity depth that used to be a second switch (the
 * signature substitution) is part of mimicry now, both in the picker and at build time; the
 * ids of stored sets are passed through untouched, whatever they hold.
 */
fun patchToggled(set: Set<String>, id: String, on: Boolean): Pair<Set<String>, Set<String>> {
    val current = set.toMutableSet()
    if (on) current.add(id) else current.remove(id)
    return current.toSet() to emptySet()
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
    val list = column(Spacing.SHEET)
    list.add(label(getString(R.string.clone_patches_hint), colorAttr = MaterialR.attr.colorOnSurfaceVariant))
    for (patch in patches) {
        list.add(
            switchRow(patch.label, patch.id in current, patch.note) { checked ->
                val (next, _) = patchToggled(current, patch.id, checked)
                current.clear()
                current.addAll(next)
            },
        )
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
 * Every patch the picker offers, in the order they are offered in. Three switches, one per
 * mechanism: exit suppression, the package-name alias, and mimicry - the one identity switch,
 * which includes the signature substitution (a stored spoofSignature without mimicry keeps
 * building as it always did; it just has no row of its own any more).
 */
fun Context.clonePatches(): List<ClonePatch> = listOf(
    // PatchIds.BYPASS and PatchIds.SPOOF_SIGNATURE are kept for the metadata of clones made
    // before this split; neither is offered as a row - BYPASS is KEEP_ALIVE's subset, and the
    // substitution is mimicry's first half.
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
        id = PatchIds.MIMICRY,
        label = getString(R.string.clone_mimicry),
        note = getString(R.string.clone_mimicry_note),
    ),
)