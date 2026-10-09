package io.github.bl3xand.apkcloner.merge

import android.content.pm.ApplicationInfo
import java.io.File
import java.util.zip.ZipFile

/** A split app to merge: an installed one, a bundle (APKS/XAPK/APKM/ZIP), or loose APK files. */
class SplitSource(
    val label: String,
    val packageName: String?,
    val versionName: String?,
    val appInfo: ApplicationInfo?,
    val entries: List<SplitEntry>,
    val baseName: String,
    val bundle: File?,
    /** Expansion files an XAPK carries next to its APKs; names of entries in [bundle]. */
    val obbEntries: List<SplitEntry> = emptyList(),
) {
    /**
     * The base and the [selected] splits as real files, base first. Entries still inside the
     * bundle are extracted into [directory]; the caller owns it.
     */
    /**
     * Unpacks the expansion files to where the app will look for them,
     * `Android/obb/<package>/` in shared storage. Installers may write there.
     */
    fun copyObbFiles(storageRoot: File) {
        val archive = bundle ?: return
        val app = packageName ?: error("Unknown package, cannot place OBB files")
        val obbRoot = File(storageRoot, "Android/obb")
        ZipFile(archive).use { zip ->
            for (entry in obbEntries) {
                // Keep the path the bundle gives below Android/obb, if it gives one.
                val relative = entry.name.substringAfter("Android/obb/", missingDelimiterValue = "")
                    .ifEmpty { "$app/${entry.name.substringAfterLast('/')}" }
                val target = File(obbRoot, relative)
                check(target.canonicalPath.startsWith(obbRoot.canonicalPath + File.separator)) { "Bad OBB path ${entry.name}" }
                target.parentFile?.mkdirs()
                zip.getInputStream(zip.getEntry(entry.name)).use { input ->
                    target.outputStream().use { input.copyTo(it, 1 shl 16) }
                }
            }
        }
    }

    fun materialize(selected: Set<String>, directory: File): List<Pair<SplitEntry, File>> {
        val chosen = entries.filter { it.name == baseName || it.name in selected }
            .sortedByDescending { it.name == baseName }
        val archive = bundle ?: return chosen.map { it to it.file!! }
        directory.deleteRecursively()
        check(directory.mkdirs()) { "Cannot create $directory" }
        return ZipFile(archive).use { zip ->
            chosen.mapIndexed { index, entry ->
                // Entry names come from the archive, so they are not used as paths.
                val target = File(directory, "$index.apk")
                zip.getInputStream(zip.getEntry(entry.name)).use { input ->
                    target.outputStream().use { input.copyTo(it, 1 shl 16) }
                }
                entry to target
            }
        }
    }
}
