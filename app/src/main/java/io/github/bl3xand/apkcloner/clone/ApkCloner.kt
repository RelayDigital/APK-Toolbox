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
     *
     * Read lazily: a build whose [CloneRequest] names its key never reads the store (only the
     * sign call's fallback does), so a desktop test can drive the whole build with its own key
     * without the Android storage behind the keys.
     */
    val keys: SigningKeys by lazy { SigningKeys.get(context) }

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

                val patcher = ManifestPatcher(request.newPackage, request.newLabel.takeIf { index == 0 }, request.removedPermissions, isSplitApk = index > 0)
                val metadata = { METADATA_ENTRY to metadataOf(patcher.oldPackage, iconFiles.isNotEmpty(), request.removedPermissions, request.patches) }
                // originalPackage (A) is what the dex still names; clonePackage (B) is what it must look
                // up. oldPackage is only known once the manifest is patched inside rebuild(), so the
                // chain reads it lazily, like the metadata entry below.
                val originalPackage = { patcher.oldPackage }
                val clonePackage = request.newPackage
                // The original's signing certificate, for the signature-spoof patch - and for mimicry, which
                // includes the substitution and cannot work without it. Read once from the source
                // APK's v2/v3 (or v1/JAR) signature block.
                // A set that says mimicry without the companion (an older pick, say) still gets the
                // full stack here: the safety net under the picker, which picks the two together.
                if (request.mimicry && !request.spoofSignature) {
                    AppLog.info("$name: mimicry includes the signature substitution; the companion is implied")
                }
                val originalCertificate = if (request.spoofSignature || request.mimicry) readCertificate(apk) else null
                // A clone whose metadata says it mimics while its dex does not is worse than a
                // loud failure: said here, so the silent no-op cannot hide.
                if (originalCertificate == null && request.mimicry) {
                    AppLog.warn("$name: mimicry cannot run - no certificate could be read from ${apk.name}; the patch is left out")
                }
                // Chain the dex patches; all of them emit no-ops/rewrites that compose in any order.
                val dexPatcher: ((String, ByteArray) -> ByteArray)? = when {
                    request.bypassSignatureCheck || request.renameAlias || request.keepAlive || originalCertificate != null || request.mimicry ->
                        DexPatchChain(request.patches, originalCertificate, originalPackage, clonePackage)::apply
                    else -> null
                }
                ApkRebuilder.rebuild(apk, unsigned, patcher, iconFiles, metadata.takeIf { index == 0 }, dexPatcher = dexPatcher)
                if (dexPatcher != null) AppLog.debug("$name: dex patches on: ${request.patches.sorted().joinToString()}")
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
            val metadata = { METADATA_ENTRY to metadataOf(patcher.oldPackage, badged, request.removedPermissions, request.patches) }
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
        private const val METADATA_PATCHES = "patches"

        private fun metadataOf(original: String, badged: Boolean, removedPermissions: Set<String>, patches: Set<String>): ByteArray = (
            "$METADATA_ORIGINAL=$original\n$METADATA_BADGE=$badged\n" +
                "$METADATA_REMOVED_PERMISSIONS=${removedPermissions.joinToString(",")}\n" +
                "$METADATA_PATCHES=${patches.joinToString(",")}\n"
            ).toByteArray()

        /**
         * The signing certificate of [apk], as the app itself would see it in
         * PackageInfo.signatures (the certificate bytes, DER). Null when it cannot be read.
         */
        fun readCertificate(apk: File): ByteArray? = try {
            val result = com.android.apksig.ApkVerifier.Builder(apk).build().verify()
            val signer = result.signerCertificates.firstOrNull() ?: result.v1SchemeSigners.firstOrNull()?.certificate
            if (signer == null) {
                AppLog.warn("SignatureSpoof: no signer certificate in ${apk.name} (verified=${result.isVerified})")
                null
            } else {
                AppLog.debug("SignatureSpoof: original certificate read from ${apk.name}, ${signer.encoded.size} bytes")
                signer.encoded
            }
        } catch (e: Throwable) {
            AppLog.warn("SignatureSpoof: cannot read the original's certificate from ${apk.name}: ${e.message ?: e}")
            null
        }

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
                    patches = values[METADATA_PATCHES].orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                )
            }
        }.getOrNull()
    }
}

/**
 * The dex patch chain, as a named class: R8 merges structurally identical lambda bodies and was
 * observed mangling the certificate capture (the spoof patch silently received null in release
 * builds while the debug build worked). A named class keeps each field honest.
 *
 * Internal (not private) so DexPatchTest can drive the patches in the chain's real order - the
 * order is half the contract: RENAME_ALIAS must run before the spoof's helper is injected.
 */
internal class DexPatchChain(
    private val patches: Set<String>,
    private val certificate: ByteArray?,
    private val originalPackage: () -> String,
    private val clonePackage: String,
) {
    fun apply(name: String, data: ByteArray): ByteArray {
        var patched = data
        val original = originalPackage()
        if (PatchIds.BYPASS in patches) patched = SignatureBypass.patchDex(patched)
        if (PatchIds.KEEP_ALIVE in patches) patched = KeepAlive.patchDex(patched)
        // RENAME_ALIAS runs BEFORE the spoof: it rewrites every const-string equal to the
        // original package, so running it after would also rewrite the mimicry helper's own
        // payload string (the original's package name, injected by the spoof) and invert the
        // mimicry into reporting the clone's name. The other direction is safe: the spoof
        // retargets getPackageInfo/getPackageName invokes, and the alias rewriter only touches
        // const-strings - the retargeted calls and the helper it reads them for compose.
        if (PatchIds.RENAME_ALIAS in patches) patched = CoreRenameAlias.patchDex(patched, original, clonePackage)
        if (certificate != null) patched = SignatureSpoof.patchDex(patched, name, certificate, original, clonePackage, PatchIds.MIMICRY in patches)
        return patched
    }

}
