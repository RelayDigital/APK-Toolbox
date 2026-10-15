package io.github.bl3xand.apkcloner.clone

/** The dex patches this clone was built with, by their stable metadata names. */
object PatchIds {
    const val BYPASS = "bypass"
    const val RENAME_ALIAS = "renameAlias"
    const val KEEP_ALIVE = "keepAlive"
    const val SPOOF_SIGNATURE = "spoofSignature"

    val ALL = listOf(BYPASS, RENAME_ALIAS, KEEP_ALIVE, SPOOF_SIGNATURE)
}

class CloneMetadata(
    val originalPackage: String,
    val badged: Boolean,
    val removedPermissions: Set<String> = emptySet(),
    /** Dex patches the clone was built with; an update keeps them on. */
    val patches: Set<String> = emptySet(),
)