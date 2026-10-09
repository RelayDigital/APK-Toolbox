package io.github.bl3xand.apkcloner.sources.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.widget.TextView
import com.google.android.material.chip.ChipGroup
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.log.AppLog

/** Periods the log can be narrowed to, in days; the last one is shown first. */
private val LOG_PERIODS_DAYS = listOf(1, 3, 7)
private const val DAY_MS = 24L * 60 * 60 * 1000

/** Shows the app's log, newest period first, with a way to share it. */
fun Context.showLogSheet() {
    AppLog.init(this)
    var days = LOG_PERIODS_DAYS.last()
    val text = TextView(this).apply {
        setTextIsSelectable(true)
        setTextAppearance(textAppearance(com.google.android.material.R.attr.textAppearanceBodySmall))
        typeface = Typeface.MONOSPACE
    }
    fun entries() = AppLog.query(after = System.currentTimeMillis() - days * DAY_MS)
    fun load() {
        text.text = entries().joinToString("\n\n").ifEmpty { getString(R.string.log_empty) }
    }
    load()
    val periods = ChipGroup(this).apply {
        isSingleSelection = true
        isSelectionRequired = true
    }
    for (option in LOG_PERIODS_DAYS) {
        periods.addView(
            filterChip(resources.getQuantityString(R.plurals.log_days, option, option), option == days) { checked ->
                if (checked) {
                    days = option
                    load()
                }
            },
        )
    }
    val view = column(Spacing.SHEET).apply {
        add(periods)
        add(text, topMargin = Spacing.BLOCK / 2)
    }
    showSheet(
        getString(R.string.settings_log), content = view,
        positive = getString(R.string.log_share), negative = getString(R.string.log_close),
    ) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, entries().joinToString("\n\n")),
                getString(R.string.settings_log),
            ),
        )
        false
    }
}
