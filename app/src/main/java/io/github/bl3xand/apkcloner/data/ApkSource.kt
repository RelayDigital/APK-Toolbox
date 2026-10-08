package io.github.bl3xand.apkcloner.data

import android.content.pm.ApplicationInfo
import io.github.bl3xand.apkcloner.clone.CloneRequest
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
    fun cloneRequest(newPackage: String, newLabel: String) = CloneRequest(
        apks = apkPaths.map(::File),
        newPackage = newPackage,
        newLabel = newLabel.takeIf { it != label },
    )
}

/** An installed app that was produced by this tool. */
class CloneInfo(
    val app: ApkSource,
    val originalPackage: String,
    /** Null when the app it was cloned from is no longer installed. */
    val original: ApkSource?,
) {
    val updateAvailable: Boolean get() = original != null && original.versionCode > app.versionCode

    /**
     * Re-cloning the original under this clone's package name. Both builds are signed with the
     * same key, so installing the result is an ordinary in-place update that keeps the data.
     */
    fun updateRequest(): CloneRequest? = original?.cloneRequest(app.packageName, app.label)
}

class InstalledApps(val apps: List<ApkSource>, val clones: List<CloneInfo>)
