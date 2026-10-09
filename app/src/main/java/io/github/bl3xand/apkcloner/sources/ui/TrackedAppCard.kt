package io.github.bl3xand.apkcloner.sources.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.databinding.ViewAppCardBinding
import io.github.bl3xand.apkcloner.sources.data.AppEntry
import io.github.bl3xand.apkcloner.ui.AppIcons
import io.github.bl3xand.apkcloner.ui.bind

/** The framed card of a tracked app, as every screen shows an app. */
fun Context.appCard(entry: AppEntry): View =
    ViewAppCardBinding.inflate(LayoutInflater.from(this)).also { it.bindTracked(entry) }.root

/**
 * Fills the shared app card for a tracked app; a tap opens its page in the system settings.
 * With [treatAsNotInstalled] the installed copy is ignored - used for a signer conflict, where the
 * card is about the build being added, not the differently-signed one already on the device.
 */
fun ViewAppCardBinding.bindTracked(entry: AppEntry, treatAsNotInstalled: Boolean = false) {
    val context = root.context
    val app = entry.app
    val info = entry.installedInfo?.applicationInfo?.takeUnless { treatAsNotInstalled }
    val version = if (treatAsNotInstalled) app.latestVersion else app.installedVersion ?: app.latestVersion
    bind(
        label = entry.name,
        subtitle = listOfNotNull(app.id.takeIf { !app.hasTempId }, version).joinToString(" · "),
        icon = info?.let { AppIcons.load(context.packageManager, it) } ?: context.getDrawable(R.drawable.ic_install),
        settingsPackage = app.devicePackage.takeIf { info != null },
    )
    imageIcon.alpha = if (info != null) 1f else 0.4f
}
