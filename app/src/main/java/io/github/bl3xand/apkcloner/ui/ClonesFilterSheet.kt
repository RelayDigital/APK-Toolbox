package io.github.bl3xand.apkcloner.ui

import android.content.Context
import com.google.android.material.chip.ChipGroup
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.ui.SourcesDialogs

/**
 * Asks which clones to show: the sheet the filter of the tracked apps has, with what there is to
 * tell clones apart by and the same categories. Null when it was closed without a choice.
 */
suspend fun Context.askClonesFilter(current: ClonesFilter): ClonesFilter? {
    var filter = current
    val view = column(Spacing.SHEET).apply {
        setPadding(dp(Spacing.SHEET), dp(2), dp(Spacing.SHEET), 0)
        add(sectionTitle(Tr.get("fltState")))
        add(
            ChipGroup(context).apply {
                addView(filterChip(getString(R.string.chip_clones_original), filter.fromOriginal) { filter = filter.copy(fromOriginal = it) })
                addView(filterChip(getString(R.string.chip_clones_source), filter.fromSource) { filter = filter.copy(fromSource = it) })
                addView(filterChip(getString(R.string.chip_no_updates), filter.notUpdated) { filter = filter.copy(notUpdated = it) })
            },
            topMargin = Spacing.UNDER_HEADING,
        )
        addDivider()
        addHeading(Tr.get("categories"))
        add(
            SourcesDialogs(context).categorySelector(filter.categories, showTitle = false) { filter = filter.copy(categories = it) },
            topMargin = Spacing.UNDER_HEADING,
        )
    }
    return if (confirm(Tr.get("filterApps"), view = view.scrollable())) filter else null
}

/** The categories a clone is filed under, chosen the way a tracked app's are. Null when nothing was changed. */
suspend fun Context.askCloneCategories(current: Set<String>): Set<String>? {
    var chosen = current
    var changed = false
    val view = column(Spacing.SHEET).apply {
        add(
            SourcesDialogs(context).categorySelector(chosen, showTitle = false) {
                chosen = it
                changed = true
            },
            topMargin = 8,
        )
    }
    return if (confirm(Tr.get("actCategories"), view = view) && changed) chosen else null
}
