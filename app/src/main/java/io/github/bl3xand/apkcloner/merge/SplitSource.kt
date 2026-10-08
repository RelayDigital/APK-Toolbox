package io.github.bl3xand.apkcloner.merge

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.OpenableColumns
import io.github.bl3xand.apkcloner.data.ApkSource
import java.io.File
import java.util.zip.ZipFile

/** One APK of a split set. [file] is null while it still sits inside [SplitSource.bundle]. */
class SplitEntry(val name: String, val size: Long, val file: File?)

/** A split app to merge: an installed one, a bundle (APKS/XAPK/APKM/ZIP), or loose APK files. */
class SplitSource(
    val label: String,
    val packageName: String?,
    val versionName: String?,
    val appInfo: ApplicationInfo?,
    val entries: List<SplitEntry>,
    val baseName: String,
    val bundle: File?,
)

class NotSplitException : Exception()

class SplitLoader(private val context: Context) {

    fun fromInstalled(app: ApkSource): SplitSource {
        val entries = app.apkPaths.mapIndexed { index, path ->
            val file = File(path)
            SplitEntry(if (index == 0) BASE_NAME else file.name, file.length(), file)
        }
        return SplitSource(app.label, app.packageName, app.versionName, app.appInfo, entries, BASE_NAME, bundle = null)
    }

    /**
     * Copies the picked documents into [workDir] (they may not be real files). A single archive
     * holding APKs is treated as a bundle; anything else as a set of loose split APKs.
     */
    fun fromUris(uris: List<Uri>, workDir: File): SplitSource {
        workDir.deleteRecursively()
        check(workDir.mkdirs()) { "Cannot create $workDir" }
        val files = uris.mapIndexed { index, uri ->
            val name = displayName(uri)?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file$index"
            File(workDir, "$index-$name").also { target ->
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it, 1 shl 16) }
                } ?: error("Cannot read $name")
            }
        }

        val bundleEntries = files.singleOrNull()?.let(::apkEntriesOf).orEmpty()
        if (bundleEntries.isNotEmpty()) {
            val bundle = files.single()
            val baseName = pickBase(bundleEntries.map { it.name to it.size })
            val preview = File(workDir, "base-preview.apk")
            ZipFile(bundle).use { zip ->
                zip.getInputStream(zip.getEntry(baseName)).use { input -> preview.outputStream().use { input.copyTo(it) } }
            }
            return describe(preview, bundleEntries, baseName, bundle, fallbackLabel = bundle.name.substringAfter('-'))
        }

        // Loose files: a lone APK is just an ordinary app, there is nothing to merge.
        val apks = files.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.size < 2) throw NotSplitException()
        val entries = apks.map { SplitEntry(it.name.substringAfter('-'), it.length(), it) }
        val baseName = pickBase(entries.map { it.name to it.size })
        return describe(entries.first { it.name == baseName }.file!!, entries, baseName, bundle = null, fallbackLabel = baseName)
    }

    private fun apkEntriesOf(file: File): List<SplitEntry> = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                .map { SplitEntry(it.name, it.size, file = null) }
                .toList()
        }
    }.getOrDefault(emptyList())

    /**
     * `base.apk` when there is one; otherwise whatever is not named like a config split (XAPK
     * calls the base `<package>.apk`), the largest of those if several qualify.
     */
    private fun pickBase(entries: List<Pair<String, Long>>): String {
        entries.firstOrNull { it.first.substringAfterLast('/') == BASE_NAME }?.let { return it.first }
        val candidates = entries.filter { (name, _) ->
            val file = name.substringAfterLast('/')
            !file.startsWith("config") && !file.startsWith("split")
        }
        return (candidates.ifEmpty { entries }).maxBy { it.second }.first
    }

    private fun describe(base: File, entries: List<SplitEntry>, baseName: String, bundle: File?, fallbackLabel: String): SplitSource {
        val packageManager = context.packageManager
        val info = packageManager.getPackageArchiveInfo(base.path, 0)
        val app = info?.applicationInfo?.apply {
            // Not filled in for archives; without them the label and icon cannot be resolved.
            sourceDir = base.path
            publicSourceDir = base.path
        }
        return SplitSource(
            label = app?.loadLabel(packageManager)?.toString() ?: fallbackLabel,
            packageName = app?.packageName,
            versionName = info?.versionName,
            appInfo = app,
            entries = entries,
            baseName = baseName,
            bundle = bundle,
        )
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private companion object {
        const val BASE_NAME = "base.apk"
    }
}
