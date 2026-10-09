package io.github.bl3xand.apkcloner.sources.install

import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.model.TrackedApp

/** Questions an install may need answered. Absent in the background, where nothing can be asked. */
interface InstallPrompts {
    /** Lets the user choose among [choices]; null cancels. */
    suspend fun pickFile(app: TrackedApp, choices: List<NamedUrl>, preselected: NamedUrl?, anyAsset: Boolean): NamedUrl?

    /** The file comes from another site than the app's source. */
    suspend fun confirmOrigin(sourceUrl: String, apkUrl: String): Boolean

    /** Shows a certificate mismatch; the result only matters when [hardBlock] is false. */
    suspend fun signingMismatch(appName: String, expected: Set<String>, actual: Set<String>, hardBlock: Boolean): Boolean
}
