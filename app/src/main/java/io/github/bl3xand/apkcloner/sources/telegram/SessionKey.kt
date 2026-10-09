package io.github.bl3xand.apkcloner.sources.telegram

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The key the Telegram session on this device is encrypted with. It is random, made once, and
 * kept wrapped by a key of the Android Keystore, which never leaves the device's secure
 * hardware - so a copy of the app's files alone does not open the session.
 */
internal object SessionKey {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "telegram_session"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREF_WRAPPED = "session_key"
    private const val KEY_BYTES = 32
    private const val WRAPPING_KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12

    /** The session key, made on first use. Throws when the keystore's key is gone and cannot unwrap it. */
    @Synchronized
    fun get(context: Context, prefsName: String): ByteArray {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.getString(PREF_WRAPPED, null)?.let { return unwrap(Base64.decode(it, Base64.NO_WRAP)) }
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(PREF_WRAPPED, Base64.encodeToString(wrap(key), Base64.NO_WRAP)).apply()
        return key
    }

    /** Forgets the key, for when the session it opened is gone for good. */
    @Synchronized
    fun forget(context: Context, prefsName: String) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().remove(PREF_WRAPPED).apply()
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(WRAPPING_KEY_BITS)
                    .build(),
            )
        }.generateKey()
    }

    /** The key under the keystore's key: the nonce the cipher chose, then what it produced. */
    private fun wrap(key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        return cipher.iv + cipher.doFinal(key)
    }

    private fun unwrap(wrapped: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, wrapped, 0, IV_BYTES))
        }
        return cipher.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
    }
}
