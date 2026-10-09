package io.github.bl3xand.apkcloner.sources.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR
import com.google.android.material.chip.ChipGroup
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.log.LogLevel
import io.github.bl3xand.apkcloner.ui.Spacing
import io.github.bl3xand.apkcloner.ui.add
import io.github.bl3xand.apkcloner.ui.column
import io.github.bl3xand.apkcloner.ui.filterChip
import io.github.bl3xand.apkcloner.ui.inScrollingRow
import io.github.bl3xand.apkcloner.ui.showSheet
import io.github.bl3xand.apkcloner.ui.textAppearance
import io.github.bl3xand.apkcloner.ui.themeColor
import java.time.LocalDate

/** The periods the log can be narrowed to, in days; the longest is as long as the log is kept. */
private val LOG_PERIODS_DAYS = listOf(1, 3, 7, AppLog.KEPT_DAYS)
private const val DEFAULT_PERIOD_DAYS = 7
private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Shows the app's log: by period and by any set of levels, each line in the colour of its level.
 * [save] is handed a file name and the text of what is shown, to be written where the user says.
 */
fun Context.showLogSheet(save: (fileName: String, text: String) -> Unit) {
    AppLog.init(this)
    var days = DEFAULT_PERIOD_DAYS
    // Any set of levels can be shown together; to begin with, only the plain messages.
    val shownLevels = mutableSetOf(LogLevel.INFO)
    val text = TextView(this).apply {
        setTextIsSelectable(true)
        setTextAppearance(textAppearance(MaterialR.attr.textAppearanceBodySmall))
        typeface = Typeface.MONOSPACE
    }
    val colors = mapOf(
        LogLevel.DEBUG to themeColor(MaterialR.attr.colorOutline),
        LogLevel.INFO to themeColor(MaterialR.attr.colorOnSurface),
        LogLevel.WARNING to themeColor(MaterialR.attr.colorTertiary),
        LogLevel.ERROR to themeColor(AppCompatR.attr.colorError),
    )
    fun entries() = AppLog.query(after = System.currentTimeMillis() - days * DAY_MS).filter { it.level in shownLevels }
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
    val levels = ChipGroup(this)
    for (level in LogLevel.entries) {
        levels.addView(
            filterChip(level.name, level in shownLevels) { checked ->
                if (checked) shownLevels.add(level) else shownLevels.remove(level)
                load()
            },
        )
    }
    val view = column(Spacing.SHEET).apply {
        add(periods.inScrollingRow())
        add(levels.inScrollingRow(), topMargin = Spacing.BLOCK / 2)
        add(text, topMargin = Spacing.BLOCK / 2)
    }
    // No button to close it: the handle and the back gesture do that.
    showSheet(getString(R.string.settings_log), content = view, positive = getString(R.string.log_save)) {
        save("apk-toolbox-${LocalDate.now()}-${days}d.log", entries().joinToString("\n"))
        false
    }
}
