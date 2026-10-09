package io.github.bl3xand.apkcloner.sources.ui

object OptionGroups {
    const val SOURCE = "grpSource"
    const val FILES = "grpFiles"
    const val VERSION = "grpVersion"
    const val UPDATES = "grpUpdates"
    const val DISPLAY = "grpDisplay"
    val ORDER = listOf(SOURCE, FILES, VERSION, UPDATES, DISPLAY)

    private val groups = mapOf(
        FILES to listOf(
            "apkFilterRegEx", "invertAPKFilter", "autoApkFilterByArch", "includeZips", "zippedApkFilterRegEx",
            "includeTarballs", "tarballedApkFilterRegEx",
        ),
        VERSION to listOf(
            "versionExtractionRegEx", "matchGroupToUse", "versionDetection", "releaseDateAsVersion", "useVersionCodeAsOSVersion",
        ),
        UPDATES to listOf(
            "trackOnly", "exemptFromBackgroundUpdates", "skipUpdateNotifications", "refreshBeforeDownload",
            "minimumUpdateAgeDays", "shizukuPretendToBeGooglePlay", "allowInsecure", "allowedSigningCertHashes",
        ),
        DISPLAY to listOf("appName", "appAuthor", "about"),
    ).flatMap { (group, keys) -> keys.map { it to group } }.toMap()

    fun of(key: String): String = groups[key] ?: SOURCE
}
