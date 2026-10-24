package io.github.bl3xand.apkcloner.sources.install

import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.model.TrackedApp

/** Questions an install may need answered. Absent in the background, where nothing can be asked. */
interface InstallPrompts {
    /** Lets the user choose among [choices]; null cancels. */
    suspend fun pickFile(app: TrackedApp, choices: List<NamedUrl>, preselected: NamedUrl?, anyAsset: Boolean): NamedUrl?

    /** The file comes from another site than the app's source. */
    suspend fun confirmOrigin(sourceUrl: String, apkUrl: String): Boolean

    /**
     * The file to install is signed differently from the app already on the device, so the
     * system would refuse it. True when the user chose to have the installed app removed -
     * its data goes with it - so that this build can be installed in its place.
     */
    suspend fun removeThenInstall(appName: String): Boolean

    /** Shows a certificate mismatch; the result only matters when [hardBlock] is false. */
    suspend fun signingMismatch(appName: String, expected: Set<String>, actual: Set<String>, hardBlock: Boolean): Boolean
}
