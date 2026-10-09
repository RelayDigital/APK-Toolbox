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
