// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Two-method seam between [HaCredentials] and the underlying cipher.
 * Production wires [AndroidKeyStoreCryptor]; JVM tests inject an
 * in-memory implementation via [HaCredentials.cryptorFactory].
 *
 * The encrypted blob format is treated as opaque by the caller: the
 * implementation is free to add IVs, version tags, or wrappers as long
 * as `decrypt(encrypt(s)) == s` holds and `decrypt` returns "" for
 * null / empty / corrupt input.
 */
interface Cryptor {
    fun encrypt(plain: String): String
    fun decrypt(blob: String?): String
}

/**
 * Production [Cryptor] backed by the AndroidKeyStore. AES-256/GCM with a
 * hardware-bound key that never leaves the secure element. Threat model
 * notes are on [HaCredentials].
 *
 * Built directly on AndroidKeyStore rather than the
 * androidx.security:security-crypto wrapper, which Google deprecated
 * (EncryptedSharedPreferences / MasterKey) at the same time it shipped
 * security-crypto 1.1.0 stable in 2025-07.
 */
class AndroidKeyStoreCryptor : Cryptor {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(out, 0)
        ciphertext.copyInto(out, iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * Returns the decrypted string, or empty if the blob is missing,
     * truncated, tampered (GCM tag mismatch), or the key has been
     * permanently invalidated (e.g. factory reset, secure-element
     * eviction). The key is not gated on user authentication, so
     * lock-screen-credential changes do not invalidate it. Empty is
     * treated as "not configured" by [HaCredentials.isConfigured]; the
     * user re-enters via Settings.
     */
    override fun decrypt(blob: String?): String {
        if (blob.isNullOrEmpty()) return ""
        return runCatching {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            if (bytes.size <= GCM_IV_LEN) return ""
            val iv = bytes.copyOfRange(0, GCM_IV_LEN)
            val ciphertext = bytes.copyOfRange(GCM_IV_LEN, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrElse { "" }
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        // Synchronized to serialise the check-then-create race on first
        // launch: two concurrent encrypts (e.g. seed-from-BuildConfig +
        // a Settings save) both seeing no alias would otherwise both
        // call generateKey and the second would silently overwrite the
        // first, invalidating any ciphertext written in between.
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "ha_credentials_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_IV_LEN = 12
        private const val GCM_TAG_BITS = 128
    }
}
