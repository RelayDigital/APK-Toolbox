package io.github.bl3xand.apkcloner.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import io.github.bl3xand.apkcloner.clone.ApkCloner
import io.github.bl3xand.apkcloner.clone.CloneMetadata
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.install.Installer
import io.github.bl3xand.apkcloner.sources.data.AppEntry
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import java.io.File

class AppRepository(private val context: Context, private val cloner: ApkCloner) {

    private val packageManager: PackageManager = context.packageManager

    fun installed(): InstalledApps {
        val packages = packageManager.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES)
            .filter { it.packageName != context.packageName && it.applicationInfo != null }
        // Asked first: it also sees to it that the tracked apps have been read.
        val asClones = runCatching { SourcesRepository.get(context).installedAsClones() }.getOrDefault(emptyMap())
        val trackedByPackage = runCatching { SourcesRepository.get(context).all() }.getOrDefault(emptyList())
            .filter { it.installedInfo != null }.associateBy { it.app.devicePackage }
        val sources = packages.associate { info ->
            val app = info.applicationInfo!!
            info.packageName to toSource(info, app, originOf(app, trackedByPackage[info.packageName]))
        }

        // Clones are recognised by the key they are signed with rather than by a local database,
        // so the list survives this app's data being cleared.
        val tracked = asClones
        val clones = packages.mapNotNull { info ->
            val signers = info.signingInfo?.apkContentsSigners ?: return@mapNotNull null
            val key = signers.firstNotNullOfOrNull { cloner.keys.matching(it.toByteArray()) } ?: return@mapNotNull null
            val source = sources.getValue(info.packageName)
            val metadata = ApkCloner.readMetadata(source.apkPaths.first()) ?: return@mapNotNull null
            CloneInfo(source, metadata.originalPackage, sources[metadata.originalPackage], key, metadata.badged, metadata.removedPermissions, tracked[info.packageName])
        }
        return InstalledApps(
            apps = sources.values.sortedBy { it.label.lowercase() },
            clones = clones.sortedBy { it.app.label.lowercase() },
        )
    }

    /**
     * Installed clones of [originalPackage], by their own package. Reads nothing about the other
     * apps, so it is cheap enough to ask for one app.
     */
    fun clonesOf(originalPackage: String): Map<String, CloneMetadata> =
        packageManager.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES).mapNotNull { info ->
            val signers = info.signingInfo?.apkContentsSigners ?: return@mapNotNull null
            if (signers.none { cloner.keys.matching(it.toByteArray()) != null }) return@mapNotNull null
            val metadata = info.applicationInfo?.sourceDir?.let(ApkCloner::readMetadata) ?: return@mapNotNull null
            if (metadata.originalPackage == originalPackage) info.packageName to metadata else null
        }.toMap()

    /** Copies the picked document to [target] (it may not be a real file) and parses it. */
    fun fromUri(uri: Uri, target: File): ApkSource? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it, 1 shl 16) }
        } ?: return null
        val info = packageManager.getPackageArchiveInfo(target.path, 0) ?: return null
        val app = info.applicationInfo ?: return null
        // Not filled in for archives; without them the label and icon cannot be resolved.
        app.sourceDir = target.path
        app.publicSourceDir = target.path
        return toSource(info, app)
    }

    /** A tracked app is from its source; anything else, from whatever installed it. */
    private fun originOf(app: ApplicationInfo, tracked: AppEntry?): String {
        if (tracked != null) return tracked.sourceName()
        val installer = runCatching { packageManager.getInstallSourceInfo(app.packageName).installingPackageName }.getOrNull()
        return when {
            installer == Installer.PLAY_STORE_PACKAGE -> "Google Play"
            installer == context.packageName -> context.getString(R.string.app_name)
            installer != null -> runCatching {
                packageManager.getApplicationInfo(installer, 0).loadLabel(packageManager).toString()
            }.getOrDefault(installer)
            app.flags and ApplicationInfo.FLAG_SYSTEM != 0 -> context.getString(R.string.origin_system)
            else -> context.getString(R.string.origin_file)
        }
    }

    private fun toSource(info: PackageInfo, app: ApplicationInfo, origin: String? = null) = ApkSource(
        packageName = app.packageName,
        label = app.loadLabel(packageManager).toString(),
        versionName = info.versionName,
        versionCode = info.longVersionCode,
        apkPaths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty(),
        isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        appInfo = app,
        origin = origin,
    )
}
