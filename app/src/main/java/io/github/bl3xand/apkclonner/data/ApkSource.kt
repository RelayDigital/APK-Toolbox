package io.github.bl3xand.apkclonner.data

import android.content.pm.ApplicationInfo

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
)

/** An installed app that was produced by this tool. */
class CloneInfo(
    val app: ApkSource,
    val originalPackage: String,
    /** Null when the app it was cloned from is no longer installed. */
    val original: ApkSource?,
) {
    val updateAvailable: Boolean get() = original != null && original.versionCode > app.versionCode
}

class InstalledApps(val apps: List<ApkSource>, val clones: List<CloneInfo>)
