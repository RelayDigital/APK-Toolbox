package io.github.bl3xand.apkcloner.data

import io.github.bl3xand.apkcloner.clone.CloneRequest
import io.github.bl3xand.apkcloner.clone.SigningKey
import io.github.bl3xand.apkcloner.sources.data.AppEntry

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
    /**
     * The tracked app this clone is installed as, when it is one that is installed as a clone of
     * itself. Its source is then what keeps the clone current, whether or not an original is
     * installed as well, and everything that rebuilds the clone goes through the source.
     */
    val tracked: AppEntry? = null,
    /** Set for this clone in the Clones tab; a clone that a source keeps current has it there. */
    private val keptAsIs: Boolean = false,
    private val ownCategories: Set<String> = emptySet(),
) {
    /** The categories it is filed under: the tracked app's, when a source keeps it current. */
    val categories: Set<String> get() = tracked?.app?.categories?.toSet() ?: ownCategories

    /** Kept at the version it has: nothing updates it by itself, and nothing offers to. */
    val frozen: Boolean get() = tracked?.app?.updatesOff ?: keptAsIs

    /** Behind what it is made from, and meant to follow it. */
    val wantsUpdate: Boolean get() = updateAvailable && !frozen

    val updateAvailable: Boolean
        get() = if (tracked != null) tracked.app.installedVersion != null && tracked.app.installedVersion != tracked.app.latestVersion
        else original != null && original.versionCode > app.versionCode

    /** The same clone as [entry] now has it: tracked by it, or by nothing. */
    fun withTracked(entry: AppEntry?) = CloneInfo(app, originalPackage, original, key, badged, removedPermissions, entry, keptAsIs, ownCategories)

    fun withFrozen(value: Boolean) = CloneInfo(app, originalPackage, original, key, badged, removedPermissions, tracked, value, ownCategories)

    fun withCategories(value: Set<String>) = CloneInfo(app, originalPackage, original, key, badged, removedPermissions, tracked, keptAsIs, value)

    /** Whether the clone can be built again: from its source, or from the installed original. */
    val canRebuild: Boolean get() = tracked != null || original != null

    /**
     * Re-cloning the original under this clone's package name. Both builds are signed with the
     * same key, so installing the result is an ordinary in-place update that keeps the data.
     */
    fun updateRequest(): CloneRequest? = original?.takeIf { tracked == null }?.cloneRequest(app.packageName, app.label, badged, key, removedPermissions)

    /**
     * Re-cloning the original under this clone's package name without [removed] instead of what it
     * was made without. Both builds are signed with the same key, so installing the result is an
     * ordinary in-place update that keeps the data.
     */
    fun permissionsRequest(removed: Set<String>): CloneRequest? =
        original?.takeIf { tracked == null }?.cloneRequest(app.packageName, app.label, badged, key, removed)
}
