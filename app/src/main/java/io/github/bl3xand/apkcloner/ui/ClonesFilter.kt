package io.github.bl3xand.apkcloner.ui

import io.github.bl3xand.apkcloner.data.CloneInfo

/** What the list of clones is narrowed to, the way the list of tracked apps is. */
data class ClonesFilter(
    /** A clone of any of these kinds is shown; nothing chosen shows every clone. */
    val kinds: Set<CloneKind> = emptySet(),
    val categories: Set<String> = emptySet(),
) {
    val isNeutral: Boolean get() = this == ClonesFilter()

    fun matches(clone: CloneInfo): Boolean =
        (kinds.isEmpty() || kinds.intersect(CloneKind.of(clone)).isNotEmpty()) &&
            (categories.isEmpty() || categories.intersect(clone.categories).isNotEmpty())
}
