package io.github.bl3xand.apkcloner.ui

import android.content.Context
import com.google.android.material.R as MaterialR
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.clone.PatchIds

/** The patches a clone can be built with: a stable [id] for the metadata, and UI text. */
data class ClonePatch(
    val id: String,
    val label: String,
    val note: String,
)

/**
 * What one flip of a patch's switch does to the set: adds or removes the patch, and keeps the
 * picker's one dependency honest - mimicry brings the signature substitution along and does not
 * let it go while it stays. The substitution alone is still a valid choice. Returns the ids
 * whose switches must be seen to change (the companion when it follows).
 */
fun patchToggled(set: Set<String>, id: String, on: Boolean): Pair<Set<String>, Set<String>> {
    val current = set.toMutableSet()
    if (on) current.add(id) else current.remove(id)
    var followed = emptySet<String>()
    if (id == PatchIds.MIMICRY && on && PatchIds.SPOOF_SIGNATURE !in current) {
        current.add(PatchIds.SPOOF_SIGNATURE)
        followed = setOf(PatchIds.SPOOF_SIGNATURE)
    }
    if (id == PatchIds.SPOOF_SIGNATURE && !on && PatchIds.MIMICRY in current) {
        current.add(PatchIds.SPOOF_SIGNATURE)
        followed = setOf(PatchIds.SPOOF_SIGNATURE)
    }
    return current.toSet() to followed
}

/**
 * Lets the user pick the patches a clone is built with, in a sheet of its own - the same pattern
 * as the permissions picker. Every patch is opt-in; [onDone] gets the chosen patch ids once the
 * choice is confirmed.
 *
 * Mimicry includes the signature substitution, so the two are picked together: checking mimicry
 * turns its companion on where the user can see it, and the companion turns itself back on if
 * it is switched off while mimicry stays - the lighter patch alone is still a valid choice.
 */
fun Context.pickClonePatches(
    patches: List<ClonePatch>,
    chosen: Set<String>,
    onCancel: (() -> Unit)? = null,
    onDone: (Set<String>) -> Unit,
) {
    var current = chosen
    val list = column(Spacing.SHEET)
    list.add(label(getString(R.string.clone_patches_hint), colorAttr = MaterialR.attr.colorOnSurfaceVariant))
    // The switch of each patch, so one patch's choice can be seen to follow from another's.
    val switches = HashMap<String, MaterialSwitch>()
    for (patch in patches) {
        val row = switchRow(patch.label, patch.id in current, patch.note) { checked ->
            val (next, followed) = patchToggled(current, patch.id, checked)
            current = next
            for (id in followed) switches[id]?.isChecked = true
        }
        switches[patch.id] = row.getChildAt(row.childCount - 1) as MaterialSwitch
    }
    var done = false
    showSheet(
        getString(R.string.clone_patches), content = list,
        positive = getString(R.string.button_done), negative = getString(android.R.string.cancel),
        onDismiss = { if (!done) onCancel?.invoke() },
    ) {
        done = true
        onDone(current)
        true
    }
}

/** The label of the button that opens the picker: how many patches are on, if any. */
fun Context.clonePatchesLabel(on: Int, total: Int): String =
    if (on == 0) getString(R.string.clone_patches) else getString(R.string.clone_patches_on, on, total)

/** Every patch the app knows, in the order they are offered in. */
fun Context.clonePatches(): List<ClonePatch> = listOf(
    // PatchIds.BYPASS is kept for the metadata of clones made before it merged into KEEP_ALIVE;
    // it is no longer offered on its own.
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
    ),
    ClonePatch(
        id = PatchIds.MIMICRY,
        label = getString(R.string.clone_mimicry),
        note = getString(R.string.clone_mimicry_note),
    ),
)