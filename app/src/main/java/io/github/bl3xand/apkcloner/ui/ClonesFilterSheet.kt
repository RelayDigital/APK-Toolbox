package io.github.bl3xand.apkcloner.ui

import android.content.Context
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
            anyOfChips(Tr.get("fltAll"), CloneKind.entries.map { it to getString(it.label) }, filter.kinds) {
                filter = filter.copy(kinds = it)
            },
            topMargin = Spacing.UNDER_HEADING,
        )
        addDivider()
        addHeading(Tr.get("categories"))
        add(
            SourcesDialogs(context).categorySelector(emptySet(), showTitle = false, selectedOrAll = filter.categories, onFilter = { filter = filter.copy(categories = it) }),
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
