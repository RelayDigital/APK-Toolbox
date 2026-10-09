package io.github.bl3xand.apkcloner.sources.ui

/** What the list is narrowed to. The search box fills [name]. */
data class AppsFilter(
    val name: String = "",
    val author: String = "",
    val id: String = "",
    val includeUpToDate: Boolean = true,
    val includeNonInstalled: Boolean = true,
    /** Apps installed as themselves, and apps installed as clones of themselves. */
    val includeOriginals: Boolean = true,
    val includeClones: Boolean = true,
    /** Apps that are kept at the version they have. */
    val includeNoUpdates: Boolean = true,
    val categories: Set<String> = emptySet(),
    val source: String = "",
) {
    val isNeutral: Boolean get() = copy(name = name.trim(), author = author.trim(), id = id.trim()) == AppsFilter()
}
