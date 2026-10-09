package io.github.bl3xand.apkcloner.ui

import androidx.core.view.isInvisible
import com.google.android.material.progressindicator.LinearProgressIndicator

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
