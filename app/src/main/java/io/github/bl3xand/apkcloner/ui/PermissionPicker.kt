package io.github.bl3xand.apkcloner.ui

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.material.R as MaterialR
import io.github.bl3xand.apkcloner.R

/** Every permission the APK at [apkPath] asks for. */
fun Context.requestedPermissionsOf(apkPath: String): List<String> = runCatching {
    packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_PERMISSIONS)?.requestedPermissions
}.getOrNull().orEmpty().distinct()

/**
 * Lets the user choose which permissions a clone keeps: every one in [requested], ticked unless
 * it is in [removed]; an unticked one is left out of the clone. [onDone] gets the permissions to
 * leave out once the choice is confirmed, [onCancel] is called when the sheet is closed without.
 *
 * A permission that was taken away stays in the list while the current version does not ask for
 * it: releases drop permissions and bring them back, and one that comes back must not slip in.
 * Permissions in [fresh] are marked as new in this version.
 */
fun Context.pickClonePermissions(
    requested: Collection<String>,
    removed: Set<String>,
    fresh: Set<String> = emptySet(),
    onCancel: (() -> Unit)? = null,
    onDone: (Set<String>) -> Unit,
) {
    val asked = requested.toSet()
    // What is new comes first: it is the reason the question is asked again.
    val shown = (asked + removed).sortedWith(compareBy({ it !in fresh }, { it }))
    val chosen = removed.toMutableSet()
    val list = column(Spacing.SHEET)
    list.add(label(getString(R.string.clone_permissions_hint), colorAttr = MaterialR.attr.colorOnSurfaceVariant))
    if (shown.isEmpty()) list.add(label(getString(R.string.clone_permissions_none)), topMargin = Spacing.BLOCK)
    for (permission in shown) {
        val note = when {
            permission !in asked -> getString(R.string.clone_permission_absent)
            permission in fresh -> getString(R.string.clone_permission_new)
            else -> null
        }
        // The last part is what tells permissions apart; the full name goes underneath.
        list.add(
            switchRow(permission.substringAfterLast('.'), permission !in chosen, listOfNotNull(permission, note).joinToString("\n")) { keep ->
                if (keep) chosen.remove(permission) else chosen.add(permission)
            },
        )
    }
    var done = false
    showSheet(
        getString(R.string.clone_permissions), content = list,
        positive = getString(R.string.button_done), negative = getString(android.R.string.cancel),
        onDismiss = { if (!done) onCancel?.invoke() },
    ) {
        done = true
        onDone(chosen.toSet())
        true
    }
}

/** The label of the button that opens the picker: how many permissions are left out, if any. */
fun Context.clonePermissionsLabel(removed: Int): String =
    if (removed == 0) getString(R.string.clone_permissions) else getString(R.string.clone_permissions_removed, removed)
