package io.github.bl3xand.apkcloner.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.databinding.ViewAppCardBinding

/**
 * Fills the outlined "which app is this" card shared by the clone and clone-detail sheets.
 * Tapping it opens the system settings page of [settingsPackage]; pass null for an app that is
 * not installed.
 */
fun ViewAppCardBinding.bind(app: ApkSource, settingsPackage: String?) {
    val context = root.context
    textLabel.text = app.label
    textPackage.text = listOfNotNull(app.packageName, app.versionName).joinToString(" · ")
    imageIcon.setImageDrawable(app.appInfo.loadIcon(context.packageManager))
    root.isClickable = settingsPackage != null
    root.setOnClickListener {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$settingsPackage"))
        )
    }
}
