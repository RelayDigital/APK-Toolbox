package io.github.bl3xand.apkcloner.ui

import android.content.res.ColorStateList
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import com.google.android.material.R as MaterialR
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.databinding.ViewNoticeBinding

/**
 * Something wrong gets the bright error colours and a button as wide as the card; what is only
 * worth knowing keeps the quieter secondary ones.
 */
fun ViewNoticeBinding.look(trouble: Boolean) {
    val context = root.context
    buttonNotice.minimumHeight = if (trouble) context.resources.getDimensionPixelSize(R.dimen.action_button_height) else 0
    buttonNotice.updateLayoutParams<ViewGroup.LayoutParams> {
        width = if (trouble) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
    }
    val background = if (trouble) MaterialR.attr.colorErrorContainer else MaterialR.attr.colorSecondaryContainer
    val foreground = if (trouble) MaterialR.attr.colorOnErrorContainer else MaterialR.attr.colorOnSecondaryContainer
    root.setCardBackgroundColor(context.themeColor(background))
    textNotice.setTextColor(context.themeColor(foreground))
    // On the error card the button is the card's colours the other way round, so that it reads.
    buttonNotice.backgroundTintList = ColorStateList.valueOf(context.themeColor(if (trouble) foreground else background))
    buttonNotice.setTextColor(context.themeColor(if (trouble) background else foreground))
}
