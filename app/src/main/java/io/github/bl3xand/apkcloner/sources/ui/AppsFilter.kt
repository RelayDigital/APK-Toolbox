package io.github.bl3xand.apkcloner.sources.ui

/** What the list is narrowed to. The search box fills [name]. */
data class AppsFilter(
    val name: String = "",
    val author: String = "",
    val id: String = "",
    /**
     * The kinds of apps to show: an app of any of them is shown. Nothing chosen shows every app,
     * so that picking one kind narrows the list to it instead of to nothing.
     */
    val kinds: Set<AppKind> = emptySet(),
    val categories: Set<String> = emptySet(),
    /** The sources to show apps of; none chosen shows the apps of every source. */
    val sources: Set<String> = emptySet(),
) {
    val isNeutral: Boolean get() = copy(name = name.trim(), author = author.trim(), id = id.trim()) == AppsFilter()
}
