package io.github.bl3xand.apkclonner.clone

import android.content.Context
import android.os.Environment
import com.android.apksig.ApkSigner
import java.io.File
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

enum class SignatureMode { DEBUG, KEEP_ORIGINAL }

class CloneRequest(
    /** Base APK first, followed by any splits. */
    val apks: List<File>,
    val newPackage: String,
    /** Null leaves the app name untouched. */
    val newLabel: String?,
    val signature: SignatureMode,
)

class ApkCloner(private val context: Context) {

    /** Returns the cloned APKs, in the same order as [CloneRequest.apks]. */
    fun clone(request: CloneRequest, onProgress: (file: String, index: Int, total: Int) -> Unit): List<File> {
        val outputDir = File(outputRoot(), request.newPackage)
        check(outputDir.isDirectory || outputDir.mkdirs()) { "Cannot create $outputDir" }
        outputDir.listFiles { file -> file.extension == "apk" }?.forEach { it.delete() }

        val keep = request.signature == SignatureMode.KEEP_ORIGINAL
        val signer = if (keep) null else debugSigner()
        val unsigned = File(context.cacheDir, "unsigned.apk")
        try {
            return request.apks.mapIndexed { index, apk ->
                val name = if (index == 0) "base.apk" else apk.name
                onProgress(name, index + 1, request.apks.size)
                val output = File(outputDir, name)
                val patcher = ManifestPatcher(request.newPackage, request.newLabel.takeIf { index == 0 })
                if (signer == null) {
                    ApkRebuilder.rebuild(apk, output, patcher, keepSignature = true)
                } else {
                    ApkRebuilder.rebuild(apk, unsigned, patcher, keepSignature = false)
                    ApkSigner.Builder(listOf(signer))
                        .setInputApk(unsigned)
                        .setOutputApk(output)
                        .setCreatedBy("ApkClonner")
                        .build()
                        .sign()
                }
                output
            }
        } finally {
            unsigned.delete()
        }
    }

    private fun debugSigner(): ApkSigner.SignerConfig {
        val key = context.assets.open("debug.pk8").use {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(it.readBytes()))
        }
        val certificate = context.assets.open("debug.x509.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        return ApkSigner.SignerConfig.Builder("CERT", key, listOf(certificate)).build()
    }

    companion object {
        fun outputRoot() = File(Environment.getExternalStorageDirectory(), "ApkClonner")
    }
}
