package io.github.bl3xand.apkcloner.sources.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import com.google.android.material.chip.ChipGroup
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.log.AppLog

/** Periods the log can be narrowed to, in days; the last one is shown first. */
private val LOG_PERIODS_DAYS = listOf(1, 3, 7)
private const val DAY_MS = 24L * 60 * 60 * 1000

/** Shows the app's log: by period and by level, each line in the colour of its level. */
fun Context.showLogSheet() {
    AppLog.init(this)
    var days = LOG_PERIODS_DAYS.last()
    var lowest = AppLog.Level.INFO
    val text = TextView(this).apply {
        setTextIsSelectable(true)
        setTextAppearance(textAppearance(com.google.android.material.R.attr.textAppearanceBodySmall))
        typeface = Typeface.MONOSPACE
    }
    val colors = mapOf(
        AppLog.Level.DEBUG to themeColor(com.google.android.material.R.attr.colorOutline),
        AppLog.Level.INFO to themeColor(com.google.android.material.R.attr.colorOnSurface),
        AppLog.Level.WARNING to themeColor(com.google.android.material.R.attr.colorTertiary),
        AppLog.Level.ERROR to themeColor(androidx.appcompat.R.attr.colorError),
    )
    fun entries() = AppLog.query(after = System.currentTimeMillis() - days * DAY_MS).filter { it.level >= lowest }
    fun load() {
        val shown = entries()
        if (shown.isEmpty()) {
            text.text = getString(R.string.log_empty)
            return
        }
        text.text = SpannableStringBuilder().apply {
            for (entry in shown) {
                val start = length
                append(entry.toString()).append("\n\n")
                setSpan(ForegroundColorSpan(colors.getValue(entry.level)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
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
    // From which level on: everything, or only what matters more.
    val levels = ChipGroup(this).apply {
        isSingleSelection = true
        isSelectionRequired = true
    }
    for (level in AppLog.Level.entries) {
        levels.addView(
            filterChip(level.name, level == lowest) { checked ->
                if (checked) {
                    lowest = level
                    load()
                }
            },
        )
    }
    val view = column(Spacing.SHEET).apply {
        add(periods)
        add(levels, topMargin = Spacing.BLOCK / 2)
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
