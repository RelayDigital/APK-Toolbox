package io.github.bl3xand.apkcloner.ui

/** Which clones the list shows: by where they get their updates, and those that get none. */
data class ClonesFilter(
    val fromOriginal: Boolean = true,
    val fromSource: Boolean = true,
    val notUpdated: Boolean = true,
    val categories: Set<String> = emptySet(),
) {
    val isNeutral: Boolean get() = this == ClonesFilter()
}
