// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.testutil

import es.jjrh.bikeradar.data.Cryptor

/**
 * Stateless [Cryptor] used by JVM tests in place of the production
 * AndroidKeyStore implementation (which Robolectric cannot provide).
 *
 * Output is non-trivially different from input so a regression that
 * accidentally bypasses the cryptor is detectable: a test that reads
 * back the raw stored value would see the prefix-and-reverse form
 * rather than the original plaintext.
 *
 * Idempotent on missing/blank input — matches the production contract
 * that a never-stored credential decrypts to "".
 */
class InMemoryCryptor : Cryptor {

    override fun encrypt(plain: String): String = PREFIX + plain.reversed()

    override fun decrypt(blob: String?): String {
        if (blob.isNullOrEmpty()) return ""
        if (!blob.startsWith(PREFIX)) return ""
        return blob.removePrefix(PREFIX).reversed()
    }

    private companion object {
        const val PREFIX = "v1:"
    }
}
