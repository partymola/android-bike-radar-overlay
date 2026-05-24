// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Decrypt-side defensive contract for [AndroidKeyStoreCryptor]: any
 * missing, truncated, or undecryptable blob must return "" rather than
 * throw, because [HaCredentials.isConfigured] treats "" as "not
 * configured" and a thrown exception here would crash every credential
 * read on a corrupted store.
 *
 * The encrypt path and a real round-trip exercise the AndroidKeyStore
 * provider, which Robolectric does not implement (it throws
 * "AndroidKeyStore not found"); JVM tests inject [InMemoryCryptor]
 * instead and the production cipher is exercised on-device. These tests
 * therefore cover only the decrypt guard branches that run before, or
 * around, the keystore call.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidKeyStoreCryptorTest {

    private val cryptor = AndroidKeyStoreCryptor()

    @Test
    fun nullBlobDecryptsToEmpty() {
        assertEquals("", cryptor.decrypt(null))
    }

    @Test
    fun emptyBlobDecryptsToEmpty() {
        assertEquals("", cryptor.decrypt(""))
    }

    @Test
    fun blobShorterThanTheIvDecryptsToEmpty() {
        // <= 12 bytes (the GCM IV length) cannot carry an IV plus ciphertext,
        // so it is rejected before any cipher work.
        val tooShort = Base64.encodeToString(ByteArray(4), Base64.NO_WRAP)
        assertEquals("", cryptor.decrypt(tooShort))
    }

    @Test
    fun blobExactlyIvLengthDecryptsToEmpty() {
        // Boundary: exactly 12 bytes is still "IV only, no ciphertext".
        val ivOnly = Base64.encodeToString(ByteArray(12), Base64.NO_WRAP)
        assertEquals("", cryptor.decrypt(ivOnly))
    }

    @Test
    fun nonBase64BlobDecryptsToEmpty() {
        // Base64.decode throws on invalid input; the runCatching wrapper
        // must swallow it into "".
        assertEquals("", cryptor.decrypt("!!! not base64 !!!"))
    }

    @Test
    fun undecryptableButWellFormedBlobDecryptsToEmpty() {
        // Longer than the IV so the decrypt reaches the cipher/keystore
        // step. On a device this is where a GCM tag mismatch on a tampered
        // blob surfaces; either way the runCatching contract is the same:
        // an undecryptable blob yields "", never an exception.
        val wellFormed = Base64.encodeToString(ByteArray(32), Base64.NO_WRAP)
        assertEquals("", cryptor.decrypt(wellFormed))
    }
}
