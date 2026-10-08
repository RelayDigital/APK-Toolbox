package io.github.bl3xand.apkcloner.merge

import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import io.github.bl3xand.apkcloner.clone.ApkCloner
import java.io.File
import java.util.zip.ZipFile

enum class MergeStep { EXTRACTING, MERGING, SAVING, SIGNING }

class MergeResult(val apk: File, val signed: Boolean, val pairip: Boolean)

/** Some splits do not belong to the same build as the base; merging them needs an explicit go-ahead. */
class SplitMismatchException(val splits: List<String>) : Exception()

/**
 * Merges split APKs into a single installable APK.
 *
 * The merge itself is ARSCLib's, and the clean-up of the merged manifest follows APKEditor's
 * `Merger` (both by REAndroid, Apache-2.0), the same pair AntiSplit-M is built on.
 */
class SplitMerger(private val cloner: ApkCloner) {

    fun merge(
        source: SplitSource,
        selected: Set<String>,
        sign: Boolean,
        force: Boolean,
        workDir: File,
        onStep: (MergeStep) -> Unit,
    ): MergeResult {
        val output = File(workDir, "out").apply { deleteRecursively(); mkdirs() }
        val extracted = File(workDir, "splits").apply { deleteRecursively(); mkdirs() }

        onStep(MergeStep.EXTRACTING)
        val chosen = source.entries.filter { it.name == source.baseName || it.name in selected }
        val files = source.bundle?.let { bundle ->
            ZipFile(bundle).use { zip ->
                chosen.mapIndexed { index, entry ->
                    // Entry names come from the archive, so they are not used as paths.
                    File(extracted, "$index.apk").also { target ->
                        zip.getInputStream(zip.getEntry(entry.name)).use { input ->
                            target.outputStream().use { input.copyTo(it, 1 shl 16) }
                        }
                    }
                }
            }
        } ?: chosen.map { it.file!! }

        val merged = File(output, "merged.apk")
        var pairip = false
        try {
            ApkBundle().use { bundle ->
                bundle.setAPKLogger(SILENT)
                files.forEachIndexed { index, file ->
                    bundle.addModule(ApkModule.loadApkFile(file, "module$index").apply { setAPKLogger(SILENT) })
                }
                val base = bundle.baseModule ?: error("No base APK among the selected files")
                val mismatched = bundle.apkModuleList.withIndex().filter { (_, module) ->
                    module !== base &&
                        (module.versionCode != base.versionCode || module.packageName != base.packageName)
                }.map { chosen[it.index].name }
                if (mismatched.isNotEmpty() && !force) throw SplitMismatchException(mismatched)

                // PairIP checks the app's integrity at start-up: a re-signed build of such an
                // app will not run, so signing it would only hide the problem.
                pairip = bundle.apkModuleList.any { module ->
                    ABIS.any { module.containsFile("lib/$it/libpairipcore.so") }
                }

                onStep(MergeStep.MERGING)
                bundle.mergeModules(force).use { module ->
                    sanitize(module)
                    onStep(MergeStep.SAVING)
                    module.writeApk(merged)
                }
            }
        } finally {
            extracted.deleteRecursively()
        }

        if (!sign || pairip) return MergeResult(merged, signed = false, pairip = pairip)
        onStep(MergeStep.SIGNING)
        val signed = File(output, "signed.apk")
        cloner.sign(merged, signed)
        merged.delete()
        return MergeResult(signed, signed = true, pairip = false)
    }

    /**
     * Strips everything that still tells the system "this app comes in splits". A merged APK
     * that keeps it fails to install on some devices.
     */
    private fun sanitize(module: ApkModule) {
        if (!module.hasAndroidManifest()) return
        val manifestBlock = module.androidManifest
        val manifest = manifestBlock.manifestElement ?: return
        val application = manifestBlock.applicationElement

        manifest.removeAttributesWithId(AndroidManifest.ID_requiredSplitTypes)
        manifest.removeAttributesWithId(AndroidManifest.ID_splitTypes)
        manifest.removeAttributesWithName(AndroidManifest.NAME_requiredSplitTypes)
        manifest.removeAttributesWithName(AndroidManifest.NAME_splitTypes)
        // Native libraries are compressed in the merged APK, so they have to be extracted on
        // install again, whatever the base said.
        for (id in intArrayOf(AndroidManifest.ID_extractNativeLibs, AndroidManifest.ID_isSplitRequired)) {
            manifest.removeAttributesWithId(id)
            application?.removeAttributesWithId(id)
        }

        if (application != null) {
            val markers = ArrayList<ResXmlElement>()
            application.getElements(AndroidManifest.TAG_meta_data).forEach { element ->
                val meta = element as ResXmlElement
                val name = meta.searchAttributeByResourceId(AndroidManifest.ID_name)
                    ?.takeIf { it.valueType == ValueType.STRING }?.valueAsString ?: return@forEach
                if (name.startsWith("com.android.vending.") || name.startsWith("com.android.stamp.")) markers += meta
            }
            for (meta in markers) {
                if (meta.searchAttributeByResourceId(AndroidManifest.ID_name)?.valueAsString == SPLITS_META) {
                    removeSplitsResource(module, meta)
                }
                application.remove(meta)
            }
        }
        manifestBlock.refresh()
    }

    /** `com.android.vending.splits` points at an XML file listing the splits; drop that file too. */
    private fun removeSplitsResource(module: ApkModule, meta: ResXmlElement) {
        val reference = (meta.searchAttributeByResourceId(AndroidManifest.ID_value)
            ?: meta.searchAttributeByResourceId(AndroidManifest.ID_resource))
            ?.takeIf { it.valueType == ValueType.REFERENCE } ?: return
        if (!module.hasTableBlock()) return
        val resource = module.tableBlock.getResource(reference.data) ?: return
        for (entry in resource) {
            val path = entry?.resValue?.valueAsString ?: continue
            module.zipEntryMap.remove(path)
            // The id may still be referenced from code, so the entry is nulled out, not deleted.
            entry.setNull(true)
            entry.typeBlock.parentSpecTypePair.removeNullEntries(entry.id)
        }
    }

    private companion object {
        const val SPLITS_META = "com.android.vending.splits"
        val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

        val SILENT = object : APKLogger {
            override fun logMessage(msg: String?) = Unit
            override fun logError(msg: String?, tr: Throwable?) = Unit
            override fun logVerbose(msg: String?) = Unit
        }
    }
}
