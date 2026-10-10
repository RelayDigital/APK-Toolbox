package io.github.bl3xand.apkcloner.clone

import android.content.Context
import com.android.apksig.ApkSigner
import io.github.bl3xand.apkcloner.log.AppLog
import java.io.File
import java.util.zip.ZipFile

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
        try {
            return request.apks.mapIndexed { index, apk ->
                val name = if (index == 0) "base.apk" else apk.name
                onProgress(name, index + 1, request.apks.size)
                val output = File(outputDir, name)

                // The icon lives in the base APK. An icon that cannot be marked is left as it
                // is: a clone without the dot beats no clone at all.
                val iconFiles = if (index == 0 && request.badgeIconOf != null) {
                    try {
                        IconBadger(context).replacements(request.badgeIconOf, request.newPackage).also {
                            if (it.isEmpty()) AppLog.warn("${request.newPackage}: the icon is built in a way that cannot be marked")
                            else AppLog.debug("${request.newPackage}: icon marked in ${it.size} file(s)")
                        }
                    } catch (e: Throwable) {
                        AppLog.warn("${request.newPackage}: the icon could not be marked: ${e.message ?: e}")
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }

                val patcher = ManifestPatcher(request.newPackage, request.newLabel.takeIf { index == 0 }, request.removedPermissions)
                val metadata = { METADATA_ENTRY to metadataOf(patcher.oldPackage, iconFiles.isNotEmpty(), request.removedPermissions) }
                ApkRebuilder.rebuild(apk, unsigned, patcher, iconFiles, metadata.takeIf { index == 0 })
                AppLog.debug("$name: ${patcher.oldPackage} renamed to ${request.newPackage}, ${unsigned.length()} bytes; signing")
                sign(unsigned, output, request.key ?: keys.active)
                output
            }
        } finally {
            unsigned.delete()
        }
    }

    /**
     * Builds the base APK of an installed clone again with another set of permissions, from the
     * clone itself and the manifest its original had: everything but the manifest is the clone's
     * own already, so nothing has to be fetched. Its splits go in as they are, being signed with
     * the same key.
     */
    fun recloneBase(installedBase: File, originalManifest: ByteArray, request: CloneRequest, outputDir: File): File {
        outputDir.deleteRecursively()
        check(outputDir.mkdirs()) { "Cannot create $outputDir" }
        val unsigned = File(outputDir, "unsigned.tmp")
        val output = File(outputDir, "base.apk")
        try {
            val badged = readMetadata(installedBase.path)?.badged ?: false
            val patcher = ManifestPatcher(request.newPackage, request.newLabel, request.removedPermissions)
            val metadata = { METADATA_ENTRY to metadataOf(patcher.oldPackage, badged, request.removedPermissions) }
            ApkRebuilder.rebuild(installedBase, unsigned, patcher, emptyMap(), metadata, originalManifest)
            sign(unsigned, output, request.key ?: keys.active)
            return output
        } finally {
            unsigned.delete()
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
        private const val METADATA_REMOVED_PERMISSIONS = "removedPermissions"

        private fun metadataOf(original: String, badged: Boolean, removedPermissions: Set<String>): ByteArray = (
            "$METADATA_ORIGINAL=$original\n$METADATA_BADGE=$badged\n" +
                "$METADATA_REMOVED_PERMISSIONS=${removedPermissions.joinToString(",")}\n"
            ).toByteArray()

        /** What a clone records about itself, or null if [baseApk] is not one of ours. */
        fun readMetadata(baseApk: String): CloneMetadata? = runCatching {
            ZipFile(baseApk).use { zip ->
                val entry = zip.getEntry(METADATA_ENTRY) ?: zip.getEntry(LEGACY_METADATA_ENTRY) ?: return null
                val values = zip.getInputStream(entry).bufferedReader().readLines()
                    .filter { '=' in it }
                    .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                val original = values[METADATA_ORIGINAL]?.takeIf { it.isNotEmpty() } ?: return null
                CloneMetadata(
                    original, badged = values[METADATA_BADGE] == "true",
                    removedPermissions = values[METADATA_REMOVED_PERMISSIONS].orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                )
            }
        }.getOrNull()
    }
}
