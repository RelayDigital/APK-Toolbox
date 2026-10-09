package io.github.bl3xand.apkcloner.sources.ui

/** One row of [SourcesDialogs.pickFromList]. */
class PickItem(val key: String, val title: String, val description: String, val badge: String?,
    /** The choice in force: marked with a tick at the end of its card. */
    val current: Boolean = false,
)
