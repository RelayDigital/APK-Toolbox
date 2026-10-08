package io.github.bl3xand.apkclonner.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File

class AppRepository(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    fun installedApps(): List<ApkSource> =
        packageManager.getInstalledPackages(0).mapNotNull { info ->
            val app = info.applicationInfo ?: return@mapNotNull null
            if (app.packageName == context.packageName) return@mapNotNull null
            ApkSource(
                packageName = app.packageName,
                label = app.loadLabel(packageManager).toString(),
                versionName = info.versionName,
                apkPaths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty(),
                isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                appInfo = app,
            )
        }.sortedBy { it.label.lowercase() }

    /** Copies the picked document into the cache (it may not be a real file) and parses it. */
    fun fromUri(uri: Uri): ApkSource? {
        val file = File(context.cacheDir, "picked.apk")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it, 1 shl 16) }
        } ?: return null
        val info = packageManager.getPackageArchiveInfo(file.path, 0) ?: return null
        val app = info.applicationInfo ?: return null
        // Not filled in for archives; without them the label and icon cannot be resolved.
        app.sourceDir = file.path
        app.publicSourceDir = file.path
        return ApkSource(
            packageName = app.packageName,
            label = app.loadLabel(packageManager).toString(),
            versionName = info.versionName,
            apkPaths = listOf(file.path),
            isSystem = false,
            appInfo = app,
        )
    }
}
