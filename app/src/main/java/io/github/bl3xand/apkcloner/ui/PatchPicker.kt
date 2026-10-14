package io.github.bl3xand.apkcloner.ui

import android.content.Context
import com.google.android.material.R as MaterialR
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.settings.AppSettings

/** The patches a clone can be built with, and the setting each one lives in. */
data class ClonePatch(
    val label: String,
    val note: String,
    val get: (AppSettings) -> Boolean,
    val set: (AppSettings, Boolean) -> Unit,
)

/**
 * Lets the user pick the patches a clone is built with, in a sheet of its own - the same pattern
 * as the permissions picker. Every patch is opt-in; [onDone] is called with the current choice
 * once it is confirmed, so the caller can keep cloning with it.
 */
fun Context.pickClonePatches(
    patches: List<ClonePatch>,
    settings: AppSettings,
    onCancel: (() -> Unit)? = null,
    onDone: () -> Unit,
) {
    val list = column(Spacing.SHEET)
    list.add(label(getString(R.string.clone_patches_hint), colorAttr = MaterialR.attr.colorOnSurfaceVariant))
    for (patch in patches) {
        list.add(switchRow(patch.label, patch.get(settings), patch.note) { checked ->
            patch.set(settings, checked)
        })
    }
    var done = false
    showSheet(
        getString(R.string.clone_patches), content = list,
        positive = getString(R.string.button_done), negative = getString(android.R.string.cancel),
        onDismiss = { if (!done) onCancel?.invoke() },
    ) {
        done = true
        onDone()
        true
    }
}

/** The label of the button that opens the picker: how many patches are on, if any. */
fun Context.clonePatchesLabel(on: Int, total: Int): String =
    if (on == 0) getString(R.string.clone_patches) else getString(R.string.clone_patches_on, on, total)