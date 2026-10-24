package io.github.bl3xand.apkcloner.ui

import androidx.core.view.isInvisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.R as MaterialR

/**
 * Shows how far something has got: [percent] of the way, or just "working on it" when that is
 * not known (null). The bar moves to a new value smoothly, and changes between the two kinds
 * out of sight - changing it while it is drawn makes it flash. With [busy] false it keeps its
 * place and shows nothing.
 */
fun LinearProgressIndicator.show(busy: Boolean, percent: Double? = null) {
    val known = percent != null && percent >= 0
    if (isIndeterminate == known) {
        isInvisible = true
        isIndeterminate = !known
    }
    if (known) setProgressCompat(percent!!.toInt(), true)
    isInvisible = !busy
}

/**
 * Puts a pull-to-refresh indicator in the colours the theme's progress indicators run in:
 * primary and secondary turns over the slightly raised surface, whatever the dynamic palette
 * of the day is.
 */
fun SwipeRefreshLayout.setSwipeColors() {
    val context = context
    setProgressBackgroundColorSchemeColor(
        MaterialColors.getColor(context, MaterialR.attr.colorSurfaceContainerHighest, 0),
    )
    setColorSchemeColors(
        MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0),
        MaterialColors.getColor(context, MaterialR.attr.colorSecondary, 0),
    )
}
