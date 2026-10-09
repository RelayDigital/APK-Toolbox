package io.github.bl3xand.apkcloner.sources.ui

import io.github.bl3xand.apkcloner.sources.data.AppEntry
import io.github.bl3xand.apkcloner.sources.data.DownloadState
import kotlinx.coroutines.flow.update

/** One line of the list: the update banner, a group header, or an app. */
sealed interface ListRow {
    data class Banner(val selectedOnly: Boolean) : ListRow
    data class Group(val key: String?, val title: String, val count: Int, val collapsed: Boolean, val color: Int?) : ListRow
    data class App(
        val entry: AppEntry,
        val download: DownloadState?,
        val selected: Boolean,
        val updatable: Boolean,
        val groupKey: String?,
        /** The package is taken by a differently-signed build, so this app is not what is installed. */
        val conflict: Boolean = false,
        /** What its source needs before it can be asked, if anything; see AppSource.signInNote. */
        val signInNote: String? = null,
    ) : ListRow
}
