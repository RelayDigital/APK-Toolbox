package io.github.bl3xand.apkcloner.clone

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.ZipFile

class CloneRequest(
    /** Base APK first, followed by any splits. */
    val apks: List<File>,
    val newPackage: String,
    /** Null leaves the app name untouched. */
    val newLabel: String?,
)

class ApkCloner(private val context: Context) {

    /**
     * Every clone is signed with this one bundled key. A signature cannot be carried over from
     * the original (it is bound to the developer's private key and to the unmodified contents),
     * and using the same key every time is what lets a clone be updated in place later.
     */
    val certificate: X509Certificate by lazy {
        context.assets.open("debug.x509.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    /** Writes the cloned APKs into [outputDir], in the same order as [CloneRequest.apks]. */
    fun clone(
        request: CloneRequest,
        outputDir: File,
        onProgress: (file: String, index: Int, total: Int) -> Unit,
    ): List<File> {
        outputDir.deleteRecursively()
        check(outputDir.mkdirs()) { "Cannot create $outputDir" }
        val key = context.assets.open("debug.pk8").use {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(it.readBytes()))
        }
        val signer = ApkSigner.SignerConfig.Builder("CERT", key, listOf(certificate)).build()
        val unsigned = File(outputDir, "unsigned.tmp")
        try {
            return request.apks.mapIndexed { index, apk ->
                val name = if (index == 0) "base.apk" else apk.name
                onProgress(name, index + 1, request.apks.size)
                val output = File(outputDir, name)
                val patcher = ManifestPatcher(request.newPackage, request.newLabel.takeIf { index == 0 })
                val metadata = { METADATA_ENTRY to "$METADATA_ORIGINAL=${patcher.oldPackage}\n".toByteArray() }
                ApkRebuilder.rebuild(apk, unsigned, patcher, metadata.takeIf { index == 0 })
                ApkSigner.Builder(listOf(signer))
                    .setInputApk(unsigned)
                    .setOutputApk(output)
                    .setCreatedBy("ApkCloner")
                    .build()
                    .sign()
                output
            }
        } finally {
            unsigned.delete()
        }
    }

    companion object {
        /** Stored in every clone's base APK so the app it was made from can be found again. */
        const val METADATA_ENTRY = "META-INF/apkcloner.properties"

        /** Written by builds from before the project was renamed; still read so those clones stay listed. */
        private const val LEGACY_METADATA_ENTRY = "META-INF/apkclonner.properties"
        private const val METADATA_ORIGINAL = "original"

        fun readOriginalPackage(baseApk: String): String? = runCatching {
            ZipFile(baseApk).use { zip ->
                val entry = zip.getEntry(METADATA_ENTRY) ?: zip.getEntry(LEGACY_METADATA_ENTRY) ?: return null
                zip.getInputStream(entry).bufferedReader().readLines()
                    .firstOrNull { it.startsWith("$METADATA_ORIGINAL=") }
                    ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
}
