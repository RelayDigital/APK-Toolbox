package io.github.bl3xand.apkcloner.merge

import io.github.bl3xand.apkcloner.compat.versionCodeLong
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Writes an installed app out as an `.apks` archive: its APK files plus the icon and metadata
 * files that SAI defined for this format (see META-FORMAT.md in the SAI repository), so the
 * result is recognised by SAI and other tools that read it.
 */
class SplitExporter(private val context: Context) {

    fun export(source: SplitSource, files: List<Pair<SplitEntry, File>>, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            // APKs are compressed already; deflating them again only costs time.
            zip.setLevel(0)
            var total = 0L
            for ((entry, file) in files) {
                zip.putNextEntry(ZipEntry(entry.name.substringAfterLast('/')))
                file.inputStream().use { total += it.copyTo(zip, 1 shl 16) }
                zip.closeEntry()
            }

            source.appInfo?.loadIcon(context.packageManager)?.let { icon ->
                val size = icon.intrinsicWidth.takeIf { it > 0 } ?: ICON_FALLBACK_SIZE
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                icon.setBounds(0, 0, size, size)
                icon.draw(Canvas(bitmap))
                zip.putNextEntry(ZipEntry("icon.png"))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }

            val info = source.packageName?.let {
                runCatching { context.packageManager.getPackageInfo(it, 0) }.getOrNull()
            }
            val v1 = JSONObject()
                .put("package", source.packageName)
                .put("label", source.label)
                .put("version_name", source.versionName)
                .put("version_code", info?.versionCodeLong ?: 0)
                .put("export_timestamp", System.currentTimeMillis())
            val v2 = JSONObject(v1.toString())
                .put("meta_version", 2)
                .put("split_apk", files.size > 1)
                .put("min_sdk", source.appInfo?.minSdkVersion ?: 0)
                .put("target_sdk", source.appInfo?.targetSdkVersion ?: 0)
                .put("backup_components", JSONArray().put(JSONObject().put("type", "apk_files").put("size", total)))
            for ((name, json) in listOf("meta.sai_v1.json" to v1, "meta.sai_v2.json" to v2)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(json.toString().toByteArray())
                zip.closeEntry()
            }
        }
    }

    private companion object {
        const val ICON_FALLBACK_SIZE = 192
    }
}
