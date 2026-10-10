package io.github.bl3xand.apkcloner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.R as AppCompatR
import androidx.core.widget.NestedScrollView
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.errorText

// Small builders for the screens of the Sources tab, which are assembled in code because most
// of their content (per-source settings, lists of sources) is only known at run time.

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.themeColor(attr: Int): Int = MaterialColors.getColor(this, attr, 0)

fun Context.textAppearance(attr: Int): Int = TypedValue().also { theme.resolveAttribute(attr, it, true) }.resourceId

fun Context.label(
    text: CharSequence,
    appearance: Int = MaterialR.attr.textAppearanceBodyMedium,
    colorAttr: Int? = null,
): TextView = TextView(this).apply {
    this.text = text
    setTextAppearance(textAppearance(appearance))
    colorAttr?.let { setTextColor(themeColor(it)) }
}

fun Context.column(padding: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), 0, dp(padding), 0)
}

fun LinearLayout.add(view: View, topMargin: Int = 0, width: Int = ViewGroup.LayoutParams.MATCH_PARENT): View {
    addView(
        view,
        LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { this.topMargin = context.dp(topMargin) },
    )
    return view
}

fun Context.sectionTitle(text: CharSequence): TextView =
    label(text, MaterialR.attr.textAppearanceTitleMediumEmphasized, AppCompatR.attr.colorPrimary)

/** A line between two groups, with the same space on both sides once the next view adds [gap] too. */
/**
 * Empty space, in dp, between the last thing drawn in a view and its bottom edge: padding,
 * the inset of a button, the blank part of a text line. Measured on the device, so that a
 * line placed after the view can end up the same distance from what the eye sees above it.
 */
private fun View?.trailingSpace(): Int = when (this) {
    null -> 0
    is MaterialButton -> if (tag == FLAT_BUTTON) Spacing.FLAT_BUTTON_INSET else Spacing.BUTTON_INSET
    is MaterialCardView -> Spacing.CARD_INSET
    is TextInputLayout -> Spacing.FIELD_INSET
    is Slider -> Spacing.SLIDER_INSET
    is LinearLayout -> Math.round(paddingBottom / resources.displayMetrics.density) +
        if (orientation == LinearLayout.VERTICAL) getChildAt(childCount - 1).trailingSpace() else Spacing.ROW_INSET
    is TextView -> Spacing.TEXT_INSET
    else -> 0
}

/**
 * A line between two groups, placed so that [Spacing.GROUP] of empty space shows between it
 * and whatever is drawn last in [after]. Followed by [addHeading], or by anything else that
 * starts [Spacing.GROUP] below it, the line sits in the middle.
 */
fun LinearLayout.addDivider(after: View? = getChildAt(childCount - 1)) {
    add(MaterialDivider(context), topMargin = (Spacing.GROUP - after.trailingSpace()).coerceAtLeast(0))
}

/** The heading of a group, [Spacing.GROUP] below a line when one precedes it. */
fun LinearLayout.addHeading(text: CharSequence, afterDivider: Boolean = true): TextView =
    add(context.sectionTitle(text), topMargin = if (afterDivider) Spacing.GROUP - Spacing.HEADING_INSET else Spacing.ITEM) as TextView

private const val FLAT_BUTTON = "flat"

/** A title with an optional description on the left and a switch on the right. */
fun Context.switchRow(
    title: CharSequence,
    checked: Boolean,
    description: CharSequence? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
): LinearLayout {
    val toggle = MaterialSwitch(this).apply {
        isChecked = checked
        isEnabled = enabled
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(Spacing.ITEM), 0, dp(Spacing.ITEM))
        val texts = column().apply {
            add(label(title, MaterialR.attr.textAppearanceTitleMedium))
            if (description != null) {
                add(
                    label(description, colorAttr = MaterialR.attr.colorOnSurfaceVariant), topMargin = 2,
                )
            }
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(16) })
        addView(toggle)
        if (enabled) setOnClickListener { toggle.toggle() }
    }
}

/** A chip that is either on or off, with the tick the rest of the app's filter chips have. */
fun Context.filterChip(text: CharSequence, checked: Boolean, onChange: (Boolean) -> Unit): Chip = Chip(this).apply {
    setChipDrawable(
        ChipDrawable.createFromAttributes(context, null, 0, MaterialR.style.Widget_Material3_Chip_Filter),
    )
    this.text = text
    setTextColor(context.getColorStateList(R.color.chip_text))
    isCheckable = true
    isChecked = checked
    // Rows of chips sit close together; the group's own spacing keeps them apart.
    setEnsureMinTouchTargetSize(false)
    setOnCheckedChangeListener { _, value -> onChange(value) }
}

/**
 * Chips to pick any of [options] by, after one - [all] - that switches every one of them on or
 * off at once and is itself on while they all are. [chosen] and what [onChange] gets are null
 * while every chip is on, which is no narrowing at all; with every chip off they are an empty
 * set, which leaves nothing to show. The chips wrap, so that all of a filter is in sight at once.
 */
fun <T> Context.anyOfChips(all: CharSequence, options: List<Pair<T, CharSequence>>, chosen: Set<T>?, onChange: (Set<T>?) -> Unit): ChipGroup {
    val everything = options.map { it.first }.toSet()
    val current = (chosen ?: everything).toMutableSet()
    val group = ChipGroup(this)
    // Set while chips are switched from here, so that they do not answer each other.
    var syncing = false
    fun report() = onChange(if (current.containsAll(everything)) null else current.toSet())
    lateinit var allChip: Chip
    val chips = options.map { (value, label) ->
        filterChip(label, value in current) { on ->
            if (syncing) return@filterChip
            if (on) current.add(value) else current.remove(value)
            syncing = true
            allChip.isChecked = current.containsAll(everything)
            syncing = false
            report()
        }
    }
    allChip = filterChip(all, current.containsAll(everything)) { on ->
        if (syncing) return@filterChip
        syncing = true
        if (on) current.addAll(everything) else current.clear()
        chips.forEach { it.isChecked = on }
        syncing = false
        report()
    }
    group.addView(allChip)
    chips.forEach(group::addView)
    return group
}

/**
 * Puts the chips in one line that scrolls sideways when they do not all fit. Left to itself a
 * group wraps into a second line, which pushes everything under it down by a row.
 */
fun ChipGroup.inScrollingRow(): View {
    isSingleLine = true
    return HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(this@inScrollingRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

/**
 * A setting that is not a switch, laid out like the rows of the main settings: its name, what
 * it is for, then the control itself.
 */
fun Context.settingBlock(title: CharSequence, description: CharSequence?, control: View): LinearLayout = column().apply {
    setPadding(0, dp(Spacing.ITEM), 0, dp(Spacing.ITEM))
    add(label(title, MaterialR.attr.textAppearanceTitleMedium))
    if (description != null) add(label(description, colorAttr = MaterialR.attr.colorOnSurfaceVariant), topMargin = 2)
    add(control, topMargin = 8)
}

fun Context.tonalButton(text: CharSequence, icon: Int? = null, onClick: () -> Unit): MaterialButton =
    MaterialButton(this, null, MaterialR.attr.materialButtonTonalStyle).apply {
        this.text = text
        icon?.let { setIconResource(it) }
        setOnClickListener { onClick() }
    }

/** A full-width row button with an icon, for the actions of a settings screen. */
fun Context.actionButton(text: CharSequence, icon: Int, onClick: () -> Unit): MaterialButton =
    tonalButton(text, icon, onClick).apply {
        // The same measures as the Widget.ApkCloner.Button.Action style of the main settings.
        minimumHeight = resources.getDimensionPixelSize(R.dimen.action_button_height)
        iconPadding = resources.getDimensionPixelSize(R.dimen.action_button_icon_padding)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        iconGravity = MaterialButton.ICON_GRAVITY_START
    }

fun Context.textButton(text: CharSequence, onClick: () -> Unit): MaterialButton =
    MaterialButton(this, null, AppCompatR.attr.borderlessButtonStyle).apply {
        tag = FLAT_BUTTON
        this.text = text
        setOnClickListener { onClick() }
    }

/** An outlined button, the same look as the secondary actions on the settings screen. */
fun Context.outlinedButton(text: CharSequence, onClick: () -> Unit): MaterialButton =
    MaterialButton(this, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

fun View.scrollable(): NestedScrollView = NestedScrollView(context).also { it.addView(this) }

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Messages.show(errorText(e))
    }
}

fun Context.copyToClipboard(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(text, text))
    Messages.show(Tr.get("copiedToClipboard"))
}

/** A passing message, shown the way the whole app shows them. */
fun Context.toast(text: CharSequence) = Messages.show(text)
