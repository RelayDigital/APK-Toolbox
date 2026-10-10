package io.github.bl3xand.apkcloner.ui

import io.github.bl3xand.apkcloner.data.CloneInfo

/** What the list of clones is narrowed to, the way the list of tracked apps is. */
data class ClonesFilter(
    // Null while it does not narrow the list - every chip of the group is on; an empty set when
    // every chip is off, which leaves nothing to show.

    /** A clone of any of these kinds is shown. */
    val kinds: Set<CloneKind>? = null,
    val categories: Set<String>? = null,
) {
    val isNeutral: Boolean get() = this == ClonesFilter()

    fun matches(clone: CloneInfo): Boolean =
        (kinds == null || kinds.intersect(CloneKind.of(clone)).isNotEmpty()) &&
            (categories == null || categories.intersect(clone.categories).isNotEmpty())
}
