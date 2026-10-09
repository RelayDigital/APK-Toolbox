package io.github.bl3xand.apkcloner.data

import android.content.pm.ApplicationInfo
import io.github.bl3xand.apkcloner.clone.CloneRequest
import io.github.bl3xand.apkcloner.clone.SigningKey
import java.io.File

/** Something that can be cloned: an installed app, or an APK file picked by the user. */
class ApkSource(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    /** Base APK first, followed by any splits. */
    val apkPaths: List<String>,
    val isSystem: Boolean,
    val appInfo: ApplicationInfo,
) {
    fun cloneRequest(
        newPackage: String,
        newLabel: String,
        badgeIcon: Boolean,
        key: SigningKey? = null,
        removedPermissions: Set<String> = emptySet(),
    ) = CloneRequest(
        apks = apkPaths.map(::File),
        newPackage = newPackage,
        newLabel = newLabel.takeIf { it != label },
        key = key,
        badgeIconOf = appInfo.takeIf { badgeIcon },
        removedPermissions = removedPermissions,
    )
}

/** An installed app that was produced by this tool. */
class CloneInfo(
    val app: ApkSource,
    val originalPackage: String,
    /** Null when the app it was cloned from is no longer installed. */
    val original: ApkSource?,
    /** The key this clone is signed with; updates must use the same one. */
    val key: SigningKey,
    /** Whether its launcher icon carries the clone mark, so an update keeps it. */
    val badged: Boolean,
    /** Permissions the clone was made without; an update leaves them out again. */
    val removedPermissions: Set<String> = emptySet(),
) {
    val updateAvailable: Boolean get() = original != null && original.versionCode > app.versionCode

    /**
     * Re-cloning the original under this clone's package name. Both builds are signed with the
     * same key, so installing the result is an ordinary in-place update that keeps the data.
     */
    fun updateRequest(): CloneRequest? = original?.cloneRequest(app.packageName, app.label, badged, key, removedPermissions)
}

class InstalledApps(val apps: List<ApkSource>, val clones: List<CloneInfo>)
