package io.github.bl3xand.apkcloner.sources.ui

/** What the list is narrowed to. The search box fills [name]. */
data class AppsFilter(
    val name: String = "",
    val author: String = "",
    val id: String = "",
    // Each of the three is null while it does not narrow the list - every chip of its group is
    // on - and an empty set when every chip is off, which leaves nothing to show.

    /** The kinds of apps to show: an app of any of them is shown. */
    val kinds: Set<AppKind>? = null,
    /** Apps filed under any of these categories. */
    val categories: Set<String>? = null,
    /** Apps of any of these sources. */
    val sources: Set<String>? = null,
) {
    val isNeutral: Boolean get() = copy(name = name.trim(), author = author.trim(), id = id.trim()) == AppsFilter()
}
