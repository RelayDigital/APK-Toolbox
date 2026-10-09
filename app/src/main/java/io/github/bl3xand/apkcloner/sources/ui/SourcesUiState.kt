package io.github.bl3xand.apkcloner.sources.ui

data class SourcesUiState(
    val rows: List<ListRow> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val refreshProgress: Double? = null,
    val selected: Set<String> = emptySet(),
    val filter: AppsFilter = AppsFilter(),
    /** Updates, new installs and track-only updates among the listed (or selected) apps. */
    val pendingUpdates: List<String> = emptyList(),
    val pendingInstalls: List<String> = emptyList(),
    val pendingTrackOnly: List<String> = emptyList(),
)
