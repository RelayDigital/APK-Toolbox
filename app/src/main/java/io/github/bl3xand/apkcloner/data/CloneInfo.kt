package io.github.bl3xand.apkcloner.data

import io.github.bl3xand.apkcloner.clone.CloneRequest
import io.github.bl3xand.apkcloner.clone.SigningKey

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

    /**
     * Re-cloning the original under this clone's package name without [removed] instead of what it
     * was made without. Both builds are signed with the same key, so installing the result is an
     * ordinary in-place update that keeps the data.
     */
    fun permissionsRequest(removed: Set<String>): CloneRequest? =
        original?.cloneRequest(app.packageName, app.label, badged, key, removed)
}
