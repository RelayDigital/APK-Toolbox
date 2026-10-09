package io.github.bl3xand.apkcloner.sources.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.errorText
import io.github.bl3xand.apkcloner.ui.Messages
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// Small builders for the screens of the Sources tab, which are assembled in code because most
// of their content (per-source settings, lists of sources) is only known at run time.

/** The few measures every sheet and dialog of this tab is built from, in dp. */
object Spacing {
    /** Side padding of a sheet or dialog body. */
    const val SHEET = 24

    /** Smallest height of anything that is tapped as a row. */
    const val ROW = 56

    /** Empty space that shows on either side of a line between two groups. */
    const val GROUP = 24

    /** Empty space that shows between two things of one group: a label and its chips, a note and a button. */
    const val BLOCK = 16

    /** What a heading or a plain label leaves, of [BLOCK], for the view placed under it. */
    const val UNDER_HEADING = BLOCK - 5
    const val UNDER_LABEL = BLOCK - 2

    // How far the visible part of a view is from its own edge; see trailingSpace().
    /**
     * A heading is pulled this much closer to the line above it. Measured to the top of its
     * capitals the gap would be even, but the eye goes by the body of the letters, and a solid
     * button or card above the line looks nearer than text does - so those get a little more.
     */
    const val HEADING_INSET = 8
    const val TEXT_INSET = 1
    const val BUTTON_INSET = 2
    const val CARD_INSET = -2
    const val FLAT_BUTTON_INSET = 15
    const val FIELD_INSET = 0
    const val SLIDER_INSET = 2

    /** A row with a switch: its text usually ends a little above the row's padding. */
    const val ROW_INSET = 3

    /** Padding above and below every setting, the same as in the main settings. */
    const val ITEM = 10
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.themeColor(attr: Int): Int = MaterialColors.getColor(this, attr, 0)

fun Context.textAppearance(attr: Int): Int = TypedValue().also { theme.resolveAttribute(attr, it, true) }.resourceId

fun Context.label(
    text: CharSequence,
    appearance: Int = com.google.android.material.R.attr.textAppearanceBodyMedium,
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
    label(text, com.google.android.material.R.attr.textAppearanceTitleMediumEmphasized, androidx.appcompat.R.attr.colorPrimary)

/** A line between two groups, with the same space on both sides once the next view adds [gap] too. */
/**
 * Empty space, in dp, between the last thing drawn in a view and its bottom edge: padding,
 * the inset of a button, the blank part of a text line. Measured on the device, so that a
 * line placed after the view can end up the same distance from what the eye sees above it.
 */
private fun View?.trailingSpace(): Int = when (this) {
    null -> 0
    is MaterialButton -> if (tag == FLAT_BUTTON) Spacing.FLAT_BUTTON_INSET else Spacing.BUTTON_INSET
    is com.google.android.material.card.MaterialCardView -> Spacing.CARD_INSET
    is com.google.android.material.textfield.TextInputLayout -> Spacing.FIELD_INSET
    is com.google.android.material.slider.Slider -> Spacing.SLIDER_INSET
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
    add(com.google.android.material.divider.MaterialDivider(context), topMargin = (Spacing.GROUP - after.trailingSpace()).coerceAtLeast(0))
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
            add(label(title, com.google.android.material.R.attr.textAppearanceTitleMedium))
            if (description != null) {
                add(
                    label(description, colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant), topMargin = 2,
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
        ChipDrawable.createFromAttributes(context, null, 0, com.google.android.material.R.style.Widget_Material3_Chip_Filter),
    )
    this.text = text
    setTextColor(context.getColorStateList(io.github.bl3xand.apkcloner.R.color.chip_text))
    isCheckable = true
    isChecked = checked
    // Rows of chips sit close together; the group's own spacing keeps them apart.
    setEnsureMinTouchTargetSize(false)
    setOnCheckedChangeListener { _, value -> onChange(value) }
}

/**
 * A setting that is not a switch, laid out like the rows of the main settings: its name, what
 * it is for, then the control itself.
 */
fun Context.settingBlock(title: CharSequence, description: CharSequence?, control: View): LinearLayout = column().apply {
    setPadding(0, dp(Spacing.ITEM), 0, dp(Spacing.ITEM))
    add(label(title, com.google.android.material.R.attr.textAppearanceTitleMedium))
    if (description != null) add(label(description, colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant), topMargin = 2)
    add(control, topMargin = 8)
}

fun Context.tonalButton(text: CharSequence, icon: Int? = null, onClick: () -> Unit): MaterialButton =
    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
        this.text = text
        icon?.let { setIconResource(it) }
        setOnClickListener { onClick() }
    }

/** A full-width row button with an icon, for the actions of a settings screen. */
fun Context.actionButton(text: CharSequence, icon: Int, onClick: () -> Unit): MaterialButton =
    tonalButton(text, icon, onClick).apply {
        // The same measures as the Widget.ApkCloner.Button.Action style of the main settings.
        minimumHeight = resources.getDimensionPixelSize(io.github.bl3xand.apkcloner.R.dimen.action_button_height)
        iconPadding = resources.getDimensionPixelSize(io.github.bl3xand.apkcloner.R.dimen.action_button_icon_padding)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        iconGravity = MaterialButton.ICON_GRAVITY_START
    }

fun Context.textButton(text: CharSequence, onClick: () -> Unit): MaterialButton =
    MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        tag = FLAT_BUTTON
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

/**
 * The one sheet every menu, question and dialog of this tab is shown in: a title, an optional
 * message, the content, and up to two buttons of the same fixed size. A [header] view - the
 * card of the app concerned - goes above the title wherever a sheet is about one app. [onPositive] returns
 * whether the sheet may close. [content] brings its own side padding.
 */
fun Context.showSheet(
    title: CharSequence,
    message: CharSequence? = null,
    content: View? = null,
    positive: String? = null,
    negative: String? = null,
    header: View? = null,
    onDismiss: (() -> Unit)? = null,
    onPositive: () -> Boolean = { true },
): BottomSheetDialog {
    val dialog = BottomSheetDialog(this)
    Messages.track(dialog)
    val side = dp(Spacing.SHEET)
    // Everything between the handle and the buttons scrolls as one piece, the app card included.
    val scrolling = column().apply {
        if (header != null) {
            // The app the sheet is about; the title under it then says what is being done.
            addView(
                header,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(side, dp(4), side, dp(16))
                },
            )
        }
        addView(
            label(
                title,
                if (header != null) com.google.android.material.R.attr.textAppearanceTitleLarge
                else com.google.android.material.R.attr.textAppearanceHeadlineSmall,
            ).apply { setPadding(side, 0, side, dp(8)) },
        )
        if (message != null) {
            addView(
                label(message, com.google.android.material.R.attr.textAppearanceBodyLarge, com.google.android.material.R.attr.colorOnSurfaceVariant)
                    .apply { setPadding(side, 0, side, dp(8)) },
            )
        }
        if (content != null) {
            // A caller's own scroll view would scroll separately from the card; take what is inside.
            val inner = if (content is NestedScrollView && content.childCount == 1) {
                content.getChildAt(0).also { content.removeView(it) }
            } else content
            addView(inner)
        }
    }
    val root = column().apply {
        addView(BottomSheetDragHandleView(context))
        addView(scrolling.scrollable(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (positive != null || negative != null) {
            val height = resources.getDimensionPixelSize(io.github.bl3xand.apkcloner.R.dimen.action_button_height)
            val row = LinearLayout(context).apply { setPadding(side, dp(16), side, dp(16)) }
            fun place(button: MaterialButton, first: Boolean) {
                button.minimumHeight = height
                row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (!first) marginStart = dp(8) })
            }
            if (negative != null) place(tonalButton(negative) { dialog.dismiss() }, first = true)
            if (positive != null) {
                place(
                    MaterialButton(context).apply {
                        text = positive
                        setOnClickListener { if (onPositive()) dialog.dismiss() }
                    },
                    first = negative == null,
                )
            }
            addView(row)
        } else {
            // Space under the text. A bare view of no set height would take the whole screen.
            addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Spacing.SHEET)))
        }
    }
    dialog.setContentView(root)
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dialog.behavior.skipCollapsed = true
    if (onDismiss != null) dialog.setOnDismissListener { onDismiss() }
    dialog.show()
    return dialog
}

/** Shows an error the way the rest of the tab does: its own text, with the URL it concerns. */
fun Context.showError(error: Any?) {
    val text = errorText(error).ifEmpty { Tr.get("unexpectedError") }
    if (text.length < 120 && !text.contains('\n')) {
        toast(text)
    } else {
        showSheet(Tr.get("error"), message = text, positive = Tr.get("ok"))
    }
}

/** A yes/no question; resumes with false when dismissed. */
suspend fun Context.confirm(
    title: CharSequence,
    message: CharSequence? = null,
    confirmText: String = Tr.get("continue"),
    cancelText: String? = Tr.get("cancel"),
    view: View? = null,
    header: View? = null,
): Boolean = suspendCancellableCoroutine { continuation ->
    var answered = false
    val dialog = showSheet(
        title, message, view, positive = confirmText, negative = cancelText, header = header,
        onDismiss = { if (!answered && continuation.isActive) continuation.resume(false) },
    ) {
        answered = true
        continuation.resume(true)
        true
    }
    continuation.invokeOnCancellation { dialog.dismiss() }
}

/**
 * Enough of Markdown for release notes: headings, emphasis, code, links and lists. Anything
 * else is shown as written.
 */
fun markdownToSpanned(markdown: String, baseUrl: String? = null): CharSequence {
    fun inline(text: String): String {
        var result = android.text.TextUtils.htmlEncode(text)
        result = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)").replace(result) { it.groupValues[1] }
        result = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)[^)]*\\)").replace(result) { match ->
            var href = match.groupValues[2]
            if (!href.startsWith("http://") && !href.startsWith("https://") && baseUrl != null) href = "$baseUrl/$href"
            "<a href=\"$href\">${match.groupValues[1]}</a>"
        }
        result = Regex("(?<![\"=>/\\w])(https?://[^\\s<)]+)").replace(result) { "<a href=\"${it.value}\">${it.value}</a>" }
        result = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__").replace(result) { "<b>${it.groupValues[1] + it.groupValues[2]}</b>" }
        result = Regex("(?<![\\w*])\\*([^*\\s][^*]*)\\*(?![\\w*])").replace(result) { "<i>${it.groupValues[1]}</i>" }
        result = Regex("`([^`]+)`").replace(result) { "<tt>${it.groupValues[1]}</tt>" }
        return result
    }
    val html = StringBuilder()
    for (rawLine in markdown.replace("\r\n", "\n").split('\n')) {
        val line = rawLine.trimEnd()
        val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
        val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$").find(line)
        when {
            heading != null -> html.append("<b>${inline(heading.groupValues[2])}</b><br>")
            bullet != null -> html.append("${"&nbsp;&nbsp;".repeat(bullet.groupValues[1].length / 2)}• ${inline(bullet.groupValues[2])}<br>")
            line.isBlank() -> html.append("<br>")
            else -> html.append(inline(line)).append("<br>")
        }
    }
    return Html.fromHtml(html.toString().removeSuffix("<br>"), Html.FROM_HTML_MODE_COMPACT)
}

fun TextView.enableLinks() {
    movementMethod = LinkMovementMethod.getInstance()
}
