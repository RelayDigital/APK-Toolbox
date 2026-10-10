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

    /** The installed version is the one to stay at: nothing offers or installs a newer one by itself. */
    const val NO_UPDATES = "doNotUpdate"

    // Installing an app as a clone of itself. These are this app's own: the reference app ignores them.
    const val CLONE_PACKAGE = "clonePackage"
    const val CLONE_ACTIVE = "cloneActive"
    const val CLONE_NAME = "cloneName"
    const val CLONE_BADGE = "cloneBadge"
    const val CLONE_REMOVED_PERMISSIONS = "cloneRemovedPermissions"
    const val CLONE_KNOWN_PERMISSIONS = "cloneKnownPermissions"
    const val CLONE_REQUESTED_PERMISSIONS = "cloneRequestedPermissions"
    const val CLONE_SOURCE_SIGNER = "cloneSourceSigner"
    const val CLONE_MANIFEST_VERSION = "cloneManifestVersion"

    /** The app is tracked only because a clone of it was handed to a source, not because the user added it. */
    const val TRACKED_FOR_CLONE = "trackedForClone"
}
