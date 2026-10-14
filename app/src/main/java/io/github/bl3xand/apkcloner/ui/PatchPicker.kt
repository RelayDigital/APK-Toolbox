package io.github.bl3xand.apkcloner.ui

import android.content.Context
import com.google.android.material.R as MaterialR
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.clone.PatchIds

/** The patches a clone can be built with: a stable [id] for the metadata, and UI text. */
data class ClonePatch(
    val id: String,
    val label: String,
    val note: String,
)

/**
 * Lets the user pick the patches a clone is built with, in a sheet of its own - the same pattern
 * as the permissions picker. Every patch is opt-in; [onDone] gets the chosen patch ids once the
 * choice is confirmed.
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
        list.add(switchRow(patch.label, patch.id in current, patch.note) { checked ->
            if (checked) current.add(patch.id) else current.remove(patch.id)
        })
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

/** Every patch the app knows, in the order they are offered in. */
fun Context.clonePatches(): List<ClonePatch> = listOf(
    ClonePatch(
        id = PatchIds.BYPASS,
        label = getString(R.string.clone_bypass),
        note = getString(R.string.clone_bypass_note),
    ),
    ClonePatch(
        id = PatchIds.RENAME_ALIAS,
        label = getString(R.string.clone_rename_alias),
        note = getString(R.string.clone_rename_alias_note),
    ),
    ClonePatch(
        id = PatchIds.KEEP_ALIVE,
        label = getString(R.string.clone_keep_alive),
        note = getString(R.string.clone_keep_alive_note),
    ),
)