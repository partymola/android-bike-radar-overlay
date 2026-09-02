// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import android.content.pm.PackageManager
import java.security.MessageDigest

/** One app, resolved from a calling UID. */
data class CallerIdentity(
    val packageName: String,
    val label: String,
)

/**
 * Turns a binder's calling UID into one app, and answers which signing
 * certificates that app can prove it owns.
 *
 * An interface so the gate's rules can be tested without a PackageManager.
 */
interface PackageIdentity {

    /**
     * Null when the UID maps to no package, or to more than one.
     *
     * More than one means a shared UID, where there is no answer to "who is
     * calling". Refusing is the only honest response, and it is rare enough
     * that guessing would trade a real security property for nothing.
     */
    fun resolve(uid: Int): CallerIdentity?

    /**
     * SHA-256 of every certificate the app can prove it owns, including ones it
     * has rotated away from.
     *
     * A set rather than one value, so a grant survives a legitimate key
     * rotation and so a multiply-signed app gives the same answer however the
     * platform happens to order its signers. Empty when the app is unknown or
     * its signing information cannot be read, which denies by construction.
     */
    fun digests(packageName: String): Set<String>

    /** Null when the app is not installed. */
    fun uidOf(packageName: String): Int?
}

/** The real one. */
class SystemPackageIdentity(private val pm: PackageManager) : PackageIdentity {

    override fun resolve(uid: Int): CallerIdentity? {
        val packageName = pm.getPackagesForUid(uid)?.singleOrNull() ?: return null
        // A label is cosmetic, so failing to read one must not deny access.
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        return CallerIdentity(packageName, label)
    }

    override fun uidOf(packageName: String): Int? = runCatching { pm.getPackageUid(packageName, 0) }.getOrNull()

    override fun digests(packageName: String): Set<String> {
        val info = runCatching {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return emptySet()
        val signing = info.signingInfo ?: return emptySet()
        // Both arrays, because they are alternatives rather than a pair: the
        // platform populates the history for a single signer and the contents
        // signers for several, and reading only one silently denies the other
        // shape. `hasSigningCertificate` is the API built for this question,
        // but Robolectric does not shadow it, so nothing could pin the answer.
        val history = runCatching { signing.signingCertificateHistory }.getOrNull() ?: emptyArray()
        val signers = runCatching { signing.apkContentsSigners }.getOrNull() ?: emptyArray()
        return (history + signers).map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
