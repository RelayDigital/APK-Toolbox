package io.github.bl3xand.apkcloner.clone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

/**
 * The keys APKs are signed with. Out of the box that is a key bundled with the app - the same
 * for everybody, and public. The user can replace it with one of their own, created here or
 * imported, which is then kept encrypted in the app's private storage.
 */
class SigningKeys private constructor(private val context: Context) {

    val bundled: SigningKey by lazy {
        val key = context.assets.open("debug.pk8").use {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(it.readBytes()))
        }
        val certificate = context.assets.open("debug.x509.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        SigningKey(key, certificate)
    }

    private val storage = File(context.filesDir, "signing/key.bin")

    @Volatile
    var custom: SigningKey? = runCatching { load() }.getOrNull()
        private set

    /** What new clones and merged APKs are signed with. */
    val active: SigningKey get() = custom ?: bundled

    /**
     * The key that produced an installed app's signature, if it is one of ours. An update has to
     * be signed with that same key, whichever is active now.
     */
    fun matching(certificateBytes: ByteArray): SigningKey? =
        listOfNotNull(custom, bundled).firstOrNull { it.signs(certificateBytes) }

    /** Creates a new key of the user's own and makes it the active one. */
    fun generate(commonName: String) {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
        val certificate = SelfSignedCertificate.create(pair.private, pair.public.encoded, commonName, VALIDITY_YEARS)
        store(SigningKey(pair.private, certificate))
    }

    /** Reads a key from a PKCS#12 or BKS keystore. [alias] may be blank to take the first key entry. */
    fun import(input: InputStream, password: CharArray, alias: String) {
        val bytes = input.readBytes()
        var failure: Exception? = null
        for (type in listOf("PKCS12", "BKS")) {
            try {
                val keystore = KeyStore.getInstance(type).apply { load(ByteArrayInputStream(bytes), password) }
                val entryAlias = alias.ifBlank { keystore.aliases().asSequence().firstOrNull(keystore::isKeyEntry) }
                    ?: error("The keystore has no key entries")
                val key = keystore.getKey(entryAlias, password) as? PrivateKey ?: error("No private key under \"$entryAlias\"")
                val certificate = keystore.getCertificate(entryAlias) as? X509Certificate ?: error("No certificate under \"$entryAlias\"")
                store(SigningKey(key, certificate))
                return
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IllegalStateException("Unsupported keystore")
    }

    /** Writes the user's own key as a password-protected PKCS#12 keystore, for safekeeping. */
    fun export(output: OutputStream, password: CharArray) {
        val key = custom ?: error("No key of your own to export")
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(EXPORT_ALIAS, key.privateKey, password, arrayOf(key.certificate))
            store(output, password)
        }
    }

    /** Back to the bundled key. Apps signed with the discarded key can no longer be updated. */
    fun reset() {
        storage.delete()
        custom = null
    }

    private fun store(key: SigningKey) {
        val plain = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                for (part in listOf(key.privateKey.encoded, key.certificate.encoded)) {
                    out.writeInt(part.size)
                    out.write(part)
                }
            }
        }.toByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        storage.parentFile?.mkdirs()
        DataOutputStream(storage.outputStream()).use { out ->
            out.writeInt(cipher.iv.size)
            out.write(cipher.iv)
            out.write(cipher.doFinal(plain))
        }
        custom = key
    }

    private fun load(): SigningKey? {
        if (!storage.exists()) return null
        val plain = DataInputStream(storage.inputStream()).use { input ->
            val iv = ByteArray(input.readInt()).also(input::readFully)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            cipher.doFinal(input.readBytes())
        }
        return DataInputStream(ByteArrayInputStream(plain)).use { input ->
            val keyBytes = ByteArray(input.readInt()).also(input::readFully)
            val certificateBytes = ByteArray(input.readInt()).also(input::readFully)
            SigningKey(
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes)),
                CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificateBytes)) as X509Certificate,
            )
        }
    }

    /**
     * The signing key has to be usable by apksig as plain key material, so it cannot live in the
     * Android Keystore itself. It is instead encrypted at rest with a key that does.
     */
    private fun wrappingKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getKey(WRAPPING_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(WRAPPING_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    companion object {
        private const val KEY_BITS = 2048
        private const val VALIDITY_YEARS = 30
        private const val EXPORT_ALIAS = "apk-toolbox"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAPPING_ALIAS = "signing-key-wrap"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        @Volatile
        private var instance: SigningKeys? = null

        /** One instance per process, so a key change is seen everywhere at once. */
        fun get(context: Context): SigningKeys = instance ?: synchronized(this) {
            instance ?: SigningKeys(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * Builds a self-signed X.509 v3 certificate by hand. The platform can generate one only for keys
 * that never leave the Android Keystore, which apksig cannot sign with.
 */
private object SelfSignedCertificate {

    private val SHA256_WITH_RSA = sequence(oid(1, 2, 840, 113549, 1, 1, 11), der(0x05, ByteArray(0)))

    fun create(privateKey: PrivateKey, publicKeyInfo: ByteArray, commonName: String, years: Int): X509Certificate {
        val name = sequence(der(0x31, sequence(oid(2, 5, 4, 3), der(0x0C, commonName.toByteArray()))))
        val notBefore = Date()
        val notAfter = Calendar.getInstance().apply { time = notBefore; add(Calendar.YEAR, years) }.time
        val tbs = sequence(
            der(0xA0, integer(BigInteger.valueOf(2))),
            integer(BigInteger(64, SecureRandom()).setBit(0)),
            SHA256_WITH_RSA,
            name,
            sequence(time(notBefore), time(notAfter)),
            name,
            publicKeyInfo,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(tbs)
            sign()
        }
        val certificate = sequence(tbs, SHA256_WITH_RSA, der(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val length = when {
            content.size < 0x80 -> byteArrayOf(content.size.toByte())
            content.size < 0x100 -> byteArrayOf(0x81.toByte(), content.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (content.size shr 8).toByte(), content.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }

    private fun sequence(vararg parts: ByteArray) = der(0x30, parts.fold(ByteArray(0)) { all, part -> all + part })

    private fun integer(value: BigInteger) = der(0x02, value.toByteArray())

    private fun oid(vararg arcs: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(arcs[0] * 40 + arcs[1])
        for (arc in arcs.drop(2)) {
            val groups = generateSequence(arc) { it shr 7 }.takeWhile { it > 0 }.map { it and 0x7F }.toList().reversed()
            groups.forEachIndexed { index, group -> out.write(if (index < groups.lastIndex) group or 0x80 else group) }
        }
        return der(0x06, out.toByteArray())
    }

    /** UTCTime up to 2049, GeneralizedTime after, as X.509 requires. */
    private fun time(date: Date): ByteArray {
        val year = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = date }.get(Calendar.YEAR)
        val (tag, pattern) = if (year < 2050) 0x17 to "yyMMddHHmmss'Z'" else 0x18 to "yyyyMMddHHmmss'Z'"
        val format = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return der(tag, format.format(date).toByteArray())
    }
}
