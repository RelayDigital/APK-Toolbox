package io.github.bl3xand.apkcloner.clone

import android.content.pm.ApplicationInfo
import java.io.File

class CloneRequest(
    /** Base APK first, followed by any splits. */
    val apks: List<File>,
    val newPackage: String,
    /** Null leaves the app name untouched. */
    val newLabel: String?,
    /**
     * Null signs with whichever key is active. Updating an installed clone has to name the key
     * that clone was signed with, or the system rejects the update.
     */
    val key: SigningKey? = null,
    /** Non-null to mark the clone's launcher icon with a coloured dot; the app whose icon to draw. */
    val badgeIconOf: ApplicationInfo? = null,
    /** Permissions of the original, by name, that the clone is made without. */
    val removedPermissions: Set<String> = emptySet(),
)
