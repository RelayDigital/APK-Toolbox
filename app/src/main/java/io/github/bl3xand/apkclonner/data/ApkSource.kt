package io.github.bl3xand.apkclonner.data

import android.content.pm.ApplicationInfo

/** Something that can be cloned: an installed app, or an APK file picked by the user. */
class ApkSource(
    val packageName: String,
    val label: String,
    val versionName: String?,
    /** Base APK first, followed by any splits. */
    val apkPaths: List<String>,
    val isSystem: Boolean,
    val appInfo: ApplicationInfo,
)
