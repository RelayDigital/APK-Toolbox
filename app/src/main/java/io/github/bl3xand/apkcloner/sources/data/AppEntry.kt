package io.github.bl3xand.apkcloner.sources.data

import android.content.pm.PackageInfo
import io.github.bl3xand.apkcloner.sources.core.CertHashes
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry

/** A tracked app together with what the system knows about its installed copy. */
class AppEntry(val app: TrackedApp, val installedInfo: PackageInfo?, val sourceType: String?) {
    val name: String get() = app.finalName
    val author: String get() = app.finalAuthor

    /** The stored APK list is stale, or the app asks for a fresh one before every download. */
    val needsRefreshBeforeDownload: Boolean
        get() = app.settings.getBool("refreshBeforeDownload") || app.apkUrls.firstOrNull()?.url == "placeholder"

    /** The source in a word or two: its short name, or the kind of source when it cannot be told. */
    fun sourceName(): String = runCatching { SourceRegistry.getSource(app.url, app.overrideSource).shortName }.getOrNull() ?: sourceType.orEmpty()

    val hasMultipleSigners: Boolean get() = installedInfo?.signingInfo?.hasMultipleSigners() ?: false

    val certificateHashes: List<String> get() = certHashesOf(installedInfo).toList()
}

fun certHashesOf(info: PackageInfo?): Set<String> {
    val signing = info?.signingInfo ?: return emptySet()
    val signatures = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
    return signatures?.map { CertHashes.format(it.toByteArray()) }?.toSet() ?: emptySet()
}

/** The version the system reports: its code or its name, as the app is set up. */
fun realInstalledVersionOf(app: TrackedApp, info: PackageInfo?): String? {
    if (info == null) return null
    return if (app.settings.getBool(SettingKeys.VERSION_CODE_AS_OS_VERSION)) info.longVersionCode.toString() else info.versionName
}
