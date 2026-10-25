package io.github.bl3xand.apkcloner.clone.dex

/**
 * The dex patches this clone can be built with, by their stable metadata names. [ALL] is every
 * id that can appear in stored metadata or settings, including ones the picker no longer offers
 * ([BYPASS] survives in the metadata of clones made before it merged into [KEEP_ALIVE]) - what
 * the picker offers now is a subset of this, kept in the UI layer.
 */
object PatchIds {
    const val BYPASS = "bypass"
    const val RENAME_ALIAS = "renameAlias"
    const val KEEP_ALIVE = "keepAlive"
    const val SPOOF_SIGNATURE = "spoofSignature"
    const val MIMICRY = "mimicry"

    val ALL = listOf(BYPASS, RENAME_ALIAS, KEEP_ALIVE, SPOOF_SIGNATURE, MIMICRY)
}

class CloneMetadata(
    val originalPackage: String,
    val badged: Boolean,
    val removedPermissions: Set<String> = emptySet(),
    /** Dex patches the clone was built with; an update keeps them on. */
    val patches: Set<String> = emptySet(),
    /**
     * The patches the last build could really apply, when that is less than [patches]: the
     * identity patches need the original's signing certificate, and a source APK whose
     * certificate cannot be read is built with them left out - the dex says nothing of them,
     * however the picked set reads. Null in builds that applied everything they were asked
     * for and in clones from before this was recorded: nothing to say there.
     */
    val patchesApplied: Set<String>? = null,
) {
    /** The picked patches the last build could not apply, or empty when all of them took. */
    val patchesNotApplied: Set<String>
        get() = if (patchesApplied == null || patchesApplied.containsAll(patches)) emptySet()
        else patches - patchesApplied!!
}