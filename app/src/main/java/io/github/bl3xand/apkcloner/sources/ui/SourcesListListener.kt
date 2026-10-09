package io.github.bl3xand.apkcloner.sources.ui

/** What can be done to the rows of the list of tracked apps. */
interface SourcesListListener {
    fun onAppClick(row: ListRow.App)
    fun onAppLongClick(row: ListRow.App)
    fun onIconClick(row: ListRow.App)
    fun onUpdateClick(row: ListRow.App)
    fun onCancelDownload(row: ListRow.App)
    fun onGroupClick(row: ListRow.Group)
    fun onBannerClick()
}
