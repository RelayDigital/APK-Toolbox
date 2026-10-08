package io.github.bl3xand.apkcloner.clone

import android.content.Context
import android.content.pm.ApplicationInfo
import com.android.apksig.ApkSigner
import java.io.File
import java.util.zip.ZipFile

class CloneRequest(
    /** Base APK first, followed by any splits. */
    val apks: List<File>,
    val newPackage: String,
    /** Null leaves the app name untouched. */
    val newLabel: String?,
    /**
     * Null signs with whichever key is active. Updating an installed clone has to name the key
     * that clone was signed with, or the system rejects the update.
     */
    val key: SigningKey? = null,
    /** Non-null to mark the clone's launcher icon with a coloured dot; the app whose icon to draw. */
    val badgeIconOf: ApplicationInfo? = null,
)

class ApkCloner(private val context: Context) {

    /**
     * A signature cannot be carried over from the original (it is bound to the developer's
     * private key and to the unmodified contents), so everything this app produces is signed
     * with one of its own keys.
     */
    val keys: SigningKeys = SigningKeys.get(context)

    /** Writes the cloned APKs into [outputDir], in the same order as [CloneRequest.apks]. */
    fun clone(
        request: CloneRequest,
        outputDir: File,
        onProgress: (file: String, index: Int, total: Int) -> Unit,
    ): List<File> {
        outputDir.deleteRecursively()
        check(outputDir.mkdirs()) { "Cannot create $outputDir" }
        val unsigned = File(outputDir, "unsigned.tmp")
        val badged = File(outputDir, "badged.tmp")
        try {
            return request.apks.mapIndexed { index, apk ->
                val name = if (index == 0) "base.apk" else apk.name
                onProgress(name, index + 1, request.apks.size)
                val output = File(outputDir, name)

                // The icon is a matter of the base APK only. Rewriting an app's resources can
                // fail on unusual ones; a clone without the dot beats no clone at all.
                var source = apk
                var marked = false
                if (index == 0 && request.badgeIconOf != null) {
                    // Catches Errors as well: running out of memory here must not take the app down.
                    marked = try {
                        IconBadger(context).apply(apk, badged, request.badgeIconOf, request.newPackage)
                    } catch (_: Throwable) {
                        false
                    }
                    if (marked) source = badged
                }

                val patcher = ManifestPatcher(request.newPackage, request.newLabel.takeIf { index == 0 })
                val metadata = {
                    METADATA_ENTRY to "$METADATA_ORIGINAL=${patcher.oldPackage}\n$METADATA_BADGE=$marked\n".toByteArray()
                }
                ApkRebuilder.rebuild(source, unsigned, patcher, metadata.takeIf { index == 0 })
                sign(unsigned, output, request.key ?: keys.active)
                output
            }
        } finally {
            unsigned.delete()
            badged.delete()
        }
    }

    /** Signs [input] with [key] (v1, v2 and v3 as the APK's minSdk allows). */
    fun sign(input: File, output: File, key: SigningKey = keys.active) {
        ApkSigner.Builder(listOf(key.signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setCreatedBy("APK Toolbox")
            .build()
            .sign()
    }

    companion object {
        /** Stored in every clone's base APK so the app it was made from can be found again. */
        const val METADATA_ENTRY = "META-INF/apkcloner.properties"

        /** Written by builds from before the project was renamed; still read so those clones stay listed. */
        private const val LEGACY_METADATA_ENTRY = "META-INF/apkclonner.properties"
        private const val METADATA_ORIGINAL = "original"
        private const val METADATA_BADGE = "badge"

        /** What a clone records about itself, or null if [baseApk] is not one of ours. */
        fun readMetadata(baseApk: String): CloneMetadata? = runCatching {
            ZipFile(baseApk).use { zip ->
                val entry = zip.getEntry(METADATA_ENTRY) ?: zip.getEntry(LEGACY_METADATA_ENTRY) ?: return null
                val values = zip.getInputStream(entry).bufferedReader().readLines()
                    .filter { '=' in it }
                    .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                val original = values[METADATA_ORIGINAL]?.takeIf { it.isNotEmpty() } ?: return null
                CloneMetadata(original, badged = values[METADATA_BADGE] == "true")
            }
        }.getOrNull()
    }
}

class CloneMetadata(val originalPackage: String, val badged: Boolean)
