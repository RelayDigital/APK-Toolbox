package io.github.bl3xand.apkcloner.sources.ui

/** One row of [SourcesDialogs.showActions]. */
class SheetAction(val icon: Int, val label: String, val danger: Boolean = false, val onClick: () -> Unit)
