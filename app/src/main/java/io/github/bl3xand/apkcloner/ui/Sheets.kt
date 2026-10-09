package io.github.bl3xand.apkcloner.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButton
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.errorText
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

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
                if (header != null) MaterialR.attr.textAppearanceTitleLarge
                else MaterialR.attr.textAppearanceHeadlineSmall,
            ).apply { setPadding(side, 0, side, dp(8)) },
        )
        if (message != null) {
            addView(
                label(message, MaterialR.attr.textAppearanceBodyLarge, MaterialR.attr.colorOnSurfaceVariant)
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
            val height = resources.getDimensionPixelSize(R.dimen.action_button_height)
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
    dialog.expandFully()
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

/** Opens a sheet at its full height at once: half open, it would hide the buttons at its bottom. */
fun BottomSheetDialog.expandFully() {
    behavior.skipCollapsed = true
    behavior.state = BottomSheetBehavior.STATE_EXPANDED
}

/** The same for a sheet that is a fragment; to be called from its onStart. */
fun BottomSheetDialogFragment.expandFully() {
    (dialog as? BottomSheetDialog)?.expandFully()
}
