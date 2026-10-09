package io.github.bl3xand.apkcloner.sources.core

/** What an APK says about itself, read without downloading it whole: its package and who signed it. */
class ApkPeek(
    val packageName: String,
    val certHashes: Set<String>,
    val versionName: String? = null,
    val versionCode: Long? = null,
    /** Every permission its manifest asks for. */
    val permissions: Set<String> = emptySet(),
)
