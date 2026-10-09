package io.github.bl3xand.apkcloner.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.databinding.ViewAppCardBinding

/**
 * Fills the outlined "which app is this" card shared by the clone and clone-detail sheets.
 * Tapping it opens the system settings page of [settingsPackage]; pass null for an app that is
 * not installed.
 */
fun ViewAppCardBinding.bind(app: ApkSource, settingsPackage: String?) = bind(
    label = app.label,
    subtitle = listOfNotNull(app.packageName, app.versionName).joinToString(" · "),
    icon = AppIcons.load(root.context.packageManager, app.appInfo),
    settingsPackage = settingsPackage,
)

fun ViewAppCardBinding.bind(label: String, subtitle: String, icon: Drawable?, settingsPackage: String?) {
    val context = root.context
    textLabel.text = label
    textPackage.text = subtitle
    imageIcon.setImageDrawable(icon)
    root.isClickable = settingsPackage != null
    root.setOnClickListener {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$settingsPackage"))
        )
    }
}
