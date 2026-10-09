package io.github.bl3xand.apkcloner.sources.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import com.google.android.material.R as MaterialR
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.data.AppEntry
import io.github.bl3xand.apkcloner.sources.form.cloneItems
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.ui.Spacing
import io.github.bl3xand.apkcloner.ui.add
import io.github.bl3xand.apkcloner.ui.column
import io.github.bl3xand.apkcloner.ui.enableLinks
import io.github.bl3xand.apkcloner.ui.label
import io.github.bl3xand.apkcloner.ui.markdownToSpanned
import io.github.bl3xand.apkcloner.ui.showSheet
import io.github.bl3xand.apkcloner.ui.toast

/** The rarely needed dialogs of one tracked app: its options and the details about it. */
class AppMenus(private val context: Context, private val viewModel: SourcesViewModel) {

    /** Address, package and the certificate of the installed copy. */
    fun showInfo(entry: AppEntry) {
        val app = entry.app
        val view = context.column(Spacing.SHEET).apply {
            fun block(title: String, value: String, mono: Boolean = false) {
                add(context.label(title, MaterialR.attr.textAppearanceTitleMedium), topMargin = 16)
                add(
                    context.label(value).apply {
                        setTextIsSelectable(true)
                        if (mono) typeface = Typeface.MONOSPACE
                    },
                    topMargin = 2,
                )
            }
            block("URL", app.url)
            block(Tr.get("appId"), app.id)
            app.apkUrls.takeIf { it.isNotEmpty() }?.let { block("APK", it.joinToString("\n") { apk -> apk.name }) }
            val hashes = entry.certificateHashes
            if (hashes.isNotEmpty()) block(Tr.plural("certificateHash", hashes.size), hashes.joinToString("\n\n"), mono = true)
            (app.additionalSettings[SettingKeys.ABOUT] as? String)?.takeIf { it.isNotEmpty() }?.let { about ->
                add(context.label(Tr.get("about"), MaterialR.attr.textAppearanceTitleMedium), topMargin = 16)
                add(TextView(context).apply {
                    text = markdownToSpanned(about)
                    enableLinks()
                }, topMargin = 2)
            }
        }
        context.showSheet(Tr.get("actInfo"), content = view, positive = Tr.get("close"), header = context.appCard(entry))
    }

    /** The per-app settings of the source. */
    fun editOptions(entry: AppEntry) {
        val source = viewModel.repo.sourceOf(entry.app)
        val form = FormView(context, cloneItems(source.combinedAppSpecificSettingFormItems), entry.app.additionalSettings, grouped = true)
        val view = context.column(Spacing.SHEET).apply { add(form) }
        context.showSheet(
            Tr.get("actOptions"), content = view, positive = Tr.get("actSave"), negative = Tr.get("cancel"), header = context.appCard(entry),
        ) {
            if (form.isValid) applyOptions(entry.app, form.values) else context.toast(Tr.get("invalidInput"))
            form.isValid
        }
    }

    private fun applyOptions(original: TrackedApp, values: Map<String, Any?>) {
        val source = viewModel.repo.sourceOf(original)
        val entry = viewModel.repo.entry(original.id) ?: return
        // The form does not show every stored setting (credentials of an overridden source, the
        // package name given when adding); those are kept.
        val saved = LinkedHashMap(values)
        original.additionalSettings.forEach { (key, value) -> saved.putIfAbsent(key, value) }
        if (source.enforceTrackOnly) {
            saved[SettingKeys.TRACK_ONLY] = true
            context.toast(Tr.get("appsFromSourceAreTrackOnly"))
        }
        var app = entry.app.copy(additionalSettings = saved)
        val detectionEnabled = saved[SettingKeys.VERSION_DETECTION] == true && original.additionalSettings[SettingKeys.VERSION_DETECTION] != true
        val dateVersionEnabled = saved[SettingKeys.RELEASE_DATE_AS_VERSION] == true && original.additionalSettings[SettingKeys.RELEASE_DATE_AS_VERSION] != true
        val dateVersionDisabled = saved[SettingKeys.RELEASE_DATE_AS_VERSION] != true && original.additionalSettings[SettingKeys.RELEASE_DATE_AS_VERSION] == true
        if (dateVersionEnabled) {
            app.releaseDate?.let { date ->
                val wasUpToDate = app.installedVersion == app.latestVersion
                app = app.copy(latestVersion = TrackedApp.toMicros(date).toString())
                if (wasUpToDate) app = app.copy(installedVersion = app.latestVersion)
            }
        } else if (dateVersionDisabled) {
            app = app.copy(installedVersion = entry.installedInfo?.versionName ?: app.installedVersion)
        }
        if (detectionEnabled) {
            app = app.copy(additionalSettings = LinkedHashMap(saved).also {
                it[SettingKeys.VERSION_DETECTION] = true
                it[SettingKeys.RELEASE_DATE_AS_VERSION] = false
            })
        }
        viewModel.saveOptions(app, detectionEnabled)
    }
}
