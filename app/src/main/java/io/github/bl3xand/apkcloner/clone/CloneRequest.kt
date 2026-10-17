package io.github.bl3xand.apkcloner.clone

import android.content.pm.ApplicationInfo
import java.io.File

class CloneRequest(
    /** Base APK first, followed by any splits. */
    val apks: List<File>,
    val newPackage: String,
    /** Null leaves the app name untouched. */
    val newLabel: String?,
    /**
     * Null signs with whichever key is active. Updating an installed clone has to name the key
     * that clone was signed with, or the system rejects the update.
     */
    val key: SigningKey? = null,
    /** Non-null to mark the clone's launcher icon with a coloured dot; the app whose icon to draw. */
    val badgeIconOf: ApplicationInfo? = null,
    /** Permissions of the original, by name, that the clone is made without. */
    val removedPermissions: Set<String> = emptySet(),
    /**
     * Dex patches the clone is built with, by their stable [PatchIds] names - the same pattern as
     * [removedPermissions]: opt-in, universal for any app, recorded in the clone's metadata and
     * kept on when the clone is rebuilt or updated.
     */
    val patches: Set<String> = emptySet(),
) {
    /** The signature-check bypass patch: an app that self-exits on a signature mismatch stays alive. */
    val bypassSignatureCheck: Boolean get() = PatchIds.BYPASS in patches

    /**
     * The package-name alias patch: rewrites the dex string constants so package-name based
     * core/plugin binding resolves under the clone package.
     */
    val renameAlias: Boolean get() = PatchIds.RENAME_ALIAS in patches

    /** The keep-alive patch: neutralizes the app's self-exit calls (System.exit and friends). */
    val keepAlive: Boolean get() = PatchIds.KEEP_ALIVE in patches

    /** The signature-spoof patch: the app sees the original's signing certificate in itself. */
    val spoofSignature: Boolean get() = PatchIds.SPOOF_SIGNATURE in patches

    /** The mimicry patch: the app reports the ORIGINAL's package name about itself (identity spoof). */
    val mimicry: Boolean get() = PatchIds.MIMICRY in patches

    companion object {
        /** What a package name has to look like for the system to take it. */
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}