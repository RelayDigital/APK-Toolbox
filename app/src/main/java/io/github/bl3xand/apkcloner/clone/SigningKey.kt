package io.github.bl3xand.apkcloner.clone

import com.android.apksig.ApkSigner
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

class SigningKey(val privateKey: PrivateKey, val certificate: X509Certificate) {

    val signerConfig: ApkSigner.SignerConfig by lazy {
        ApkSigner.SignerConfig.Builder("CERT", privateKey, listOf(certificate)).build()
    }

    /** Colon-separated SHA-256 of the certificate, the way Android tooling prints it. */
    val fingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }
    }

    fun signs(certificateBytes: ByteArray) = certificate.encoded.contentEquals(certificateBytes)
}
