package io.github.bl3xand.apkcloner.ui

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.material.R as MaterialR
import io.github.bl3xand.apkcloner.R

/**
 * Lets the user choose which permissions of the APK at [apkPath] a clone keeps: every one it asks
 * for, ticked unless it is in [removed]; an unticked one is left out of the clone. [onDone] gets
 * the permissions to leave out once the choice is confirmed.
 */
fun Context.pickClonePermissions(apkPath: String, removed: Set<String>, onDone: (Set<String>) -> Unit) {
    val requested = runCatching {
        packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_PERMISSIONS)?.requestedPermissions
    }.getOrNull().orEmpty().distinct().sorted()
    val chosen = removed.toMutableSet()
    val list = column(Spacing.SHEET)
    list.add(label(getString(R.string.clone_permissions_hint), colorAttr = MaterialR.attr.colorOnSurfaceVariant))
    if (requested.isEmpty()) list.add(label(getString(R.string.clone_permissions_none)), topMargin = Spacing.BLOCK)
    for (permission in requested) {
        // The last part is what tells permissions apart; the full name goes underneath.
        list.add(
            switchRow(permission.substringAfterLast('.'), permission !in chosen, permission) { keep ->
                if (keep) chosen.remove(permission) else chosen.add(permission)
            },
        )
    }
    showSheet(
        getString(R.string.clone_permissions), content = list,
        positive = getString(R.string.button_done), negative = getString(android.R.string.cancel),
    ) {
        // Only what the APK really asks for counts: a stale name would never go away again.
        onDone(chosen.intersect(requested.toSet()))
        true
    }
}

/** The label of the button that opens the picker: how many permissions are left out, if any. */
fun Context.clonePermissionsLabel(removed: Int): String =
    if (removed == 0) getString(R.string.clone_permissions) else getString(R.string.clone_permissions_removed, removed)
