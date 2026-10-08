package io.github.bl3xand.apkcloner.sources.model

/**
 * Names of the per-app settings that code outside a single source reads or writes. They are
 * part of the stored format, which is shared with the reference app, so they never change.
 */
object SettingKeys {
    const val TRACK_ONLY = "trackOnly"
    const val VERSION_DETECTION = "versionDetection"
    const val RELEASE_DATE_AS_VERSION = "releaseDateAsVersion"
    const val PRETEND_GOOGLE_PLAY = "shizukuPretendToBeGooglePlay"
    const val EXEMPT_FROM_BACKGROUND_UPDATES = "exemptFromBackgroundUpdates"
    const val SKIP_UPDATE_NOTIFICATIONS = "skipUpdateNotifications"
    const val VERSION_CODE_AS_OS_VERSION = "useVersionCodeAsOSVersion"
    const val ABOUT = "about"
    const val INCLUDE_PRERELEASES = "includePrereleases"
    const val APP_ID = "appId"
}
