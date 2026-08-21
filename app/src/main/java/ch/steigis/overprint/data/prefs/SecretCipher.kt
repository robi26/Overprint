package ch.steigis.overprint.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256/GCM envelope for the two secrets that have to survive a restart: the Garmin
 * password and the OAuth access token.
 *
 * The key is generated inside the Android Keystore, is not exportable, and is never part
 * of a device backup, so the ciphertext kept in DataStore is worthless on any other
 * device. Failures are deliberately silent and fail closed: [encrypt] returns null so the
 * caller drops the secret rather than persisting it in the clear, and [decrypt] returns an
 * empty string so the user is simply asked to sign in again.
 */
internal object SecretCipher {
    private const val TAG = "SecretCipher"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "connectstats.secrets.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Marks values written by this class, so a legacy plaintext value is never fed to the cipher. */
    const val PREFIX = "gcm1:"

    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
        }.getOrElse { err ->
            Log.w(TAG, "Could not encrypt secret (${err.javaClass.simpleName}); dropping it")
            null
        }
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return ""
        return runCatching {
            val packed = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            require(packed.size > IV_BYTES) { "ciphertext too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES),
            )
            String(
                cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES),
                Charsets.UTF_8,
            )
        }.getOrElse { err ->
            Log.w(TAG, "Could not decrypt secret (${err.javaClass.simpleName}); sign in again")
            ""
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
