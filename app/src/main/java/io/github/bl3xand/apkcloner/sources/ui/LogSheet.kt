package io.github.bl3xand.apkcloner.sources.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.R as AppCompatR
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.R as MaterialR
import com.google.android.material.chip.ChipGroup
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.log.LogEntry
import io.github.bl3xand.apkcloner.log.LogLevel
import io.github.bl3xand.apkcloner.ui.Spacing
import io.github.bl3xand.apkcloner.ui.add
import io.github.bl3xand.apkcloner.ui.column
import io.github.bl3xand.apkcloner.ui.filterChip
import io.github.bl3xand.apkcloner.ui.inScrollingRow
import io.github.bl3xand.apkcloner.ui.showSheet
import io.github.bl3xand.apkcloner.ui.textAppearance
import io.github.bl3xand.apkcloner.ui.textButton
import io.github.bl3xand.apkcloner.ui.themeColor
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The periods the log can be narrowed to, in days; the longest is as long as the log is kept. */
private val LOG_PERIODS_DAYS = listOf(1, 3, 7, AppLog.KEPT_DAYS)
private const val DEFAULT_PERIOD_DAYS = 7
private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * How many of the newest entries are laid out at once. A whole month of a busy install can run
 * to tens of thousands of lines; a spanned text of that size costs seconds of layout, so the
 * sheet shows the newest ones and offers the rest through a button. The save action still
 * writes everything the period and the levels select, shown or not.
 */
private const val PAGE_ENTRIES = 2000

/**
 * Shows the app's log: by period and by any set of levels, each line in the colour of its level.
 * [save] is handed a file name and the text of what is shown, to be written where the user says.
 */
fun Context.showLogSheet(owner: LifecycleOwner, save: (fileName: String, text: String) -> Unit) {
    AppLog.init(this)
    var days = DEFAULT_PERIOD_DAYS
    // Any set of levels can be shown together; to begin with, everything that was kept.
    val shownLevels = mutableSetOf(*LogLevel.entries.toTypedArray())
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
    // What is laid out now, of the selection; grows by a page whenever the button is pressed.
    var shownCount = PAGE_ENTRIES
    var more: View? = null
    // The chips re-ask for the whole log on every change; one build at a time, the newest first.
    var loading = false
    fun load() {
        if (loading) return
        loading = true
        owner.lifecycleScope.launch {
            // The query and the spanned text are the heavy part with a month of entries in the
            // store, and neither belongs on the thread that has to keep the sheet scrolling.
            val entries = withContext(Dispatchers.IO) {
                AppLog.query(after = System.currentTimeMillis() - days * DAY_MS).filter { it.level in shownLevels }
            }
            val newestFirst = entries.asReversed()
            text.text = when {
                entries.isEmpty() -> getString(R.string.log_empty)
                else -> spanned(newestFirst.take(shownCount), colors)
            }
            val count = newestFirst.size
            if (count > shownCount) {
                val rest = count - shownCount
                if (more == null) {
                    more = textButton(
                        resources.getQuantityString(R.plurals.log_show_more, rest, rest),
                    ) {
                        shownCount += PAGE_ENTRIES
                        load()
                    }.also { button -> (text.parent as? android.view.ViewGroup)?.addView(button) }
                } else {
                    (more as TextView).text =
                        resources.getQuantityString(R.plurals.log_show_more, rest, rest)
                }
            } else {
                more?.visibility = View.GONE
            }
            loading = false
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
                    shownCount = PAGE_ENTRIES
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
        // Everything the period and the levels select is saved, however much of it is laid out.
        owner.lifecycleScope.launch {
            val selected = withContext(Dispatchers.IO) {
                AppLog.query(after = System.currentTimeMillis() - days * DAY_MS)
                    .filter { it.level in shownLevels }.joinToString("\n") { it.toString() }
            }
            save("apk-toolbox-${LocalDate.now()}-${days}d.log", selected)
        }
        false
    }
}

/** The newest [entries] as one monospace body, a line per entry coloured by its level. */
private fun spanned(entries: List<LogEntry>, colors: Map<LogLevel, Int>): CharSequence =
    SpannableStringBuilder().apply {
        for (entry in entries) {
            val start = length
            append(entry.toString()).append("\n\n")
            setSpan(ForegroundColorSpan(colors.getValue(entry.level)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }