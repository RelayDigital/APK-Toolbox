package io.github.bl3xand.apkcloner.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable

/**
 * App icons as a launcher shows them. An adaptive icon is used as it is; a plain picture is put
 * on a white plate at a reduced size, the way a launcher fits it into its mask. Every list of
 * the app loads icons here, so an app looks the same in all of them - and the dot of a clone
 * comes out the same size whatever kind of icon the original has.
 */
object AppIcons {
    /** Share of the visible icon a plain picture is shrunk to; the same figure the dot of a clone is drawn for. */
    private const val LEGACY_SCALE = 0.62f

    // An adaptive layer is half again as large as what is seen of it.
    private const val LAYER_TO_VISIBLE = 1.5f
    private const val LEGACY_INSET = (1f - LEGACY_SCALE / LAYER_TO_VISIBLE) / 2f

    fun load(packageManager: PackageManager, app: ApplicationInfo): Drawable = wrap(app.loadIcon(packageManager))

    fun wrap(icon: Drawable): Drawable =
        if (icon is AdaptiveIconDrawable) icon
        else AdaptiveIconDrawable(ColorDrawable(Color.WHITE), InsetDrawable(icon, LEGACY_INSET))
}
