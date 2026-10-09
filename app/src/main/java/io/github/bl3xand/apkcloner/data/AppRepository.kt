package io.github.bl3xand.apkcloner.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import io.github.bl3xand.apkcloner.clone.ApkCloner
import java.io.File

class AppRepository(private val context: Context, private val cloner: ApkCloner) {

    private val packageManager: PackageManager = context.packageManager

    fun installed(): InstalledApps {
        val packages = packageManager.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES)
            .filter { it.packageName != context.packageName && it.applicationInfo != null }
        val sources = packages.associate { it.packageName to toSource(it, it.applicationInfo!!) }

        // Clones are recognised by the key they are signed with rather than by a local database,
        // so the list survives this app's data being cleared.
        val clones = packages.mapNotNull { info ->
            val signers = info.signingInfo?.apkContentsSigners ?: return@mapNotNull null
            val key = signers.firstNotNullOfOrNull { cloner.keys.matching(it.toByteArray()) } ?: return@mapNotNull null
            val source = sources.getValue(info.packageName)
            val metadata = ApkCloner.readMetadata(source.apkPaths.first()) ?: return@mapNotNull null
            CloneInfo(source, metadata.originalPackage, sources[metadata.originalPackage], key, metadata.badged, metadata.removedPermissions)
        }
        return InstalledApps(
            apps = sources.values.sortedBy { it.label.lowercase() },
            clones = clones.sortedBy { it.app.label.lowercase() },
        )
    }

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

    private fun toSource(info: PackageInfo, app: ApplicationInfo) = ApkSource(
        packageName = app.packageName,
        label = app.loadLabel(packageManager).toString(),
        versionName = info.versionName,
        versionCode = info.longVersionCode,
        apkPaths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty(),
        isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        appInfo = app,
    )
}
