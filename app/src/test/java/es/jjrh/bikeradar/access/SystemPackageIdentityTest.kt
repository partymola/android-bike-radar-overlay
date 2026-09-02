// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.security.MessageDigest

/**
 * Resolving a calling UID to one app, and to the certificates it can prove.
 *
 * Fixtures populate `signingInfo`, not the legacy `signatures` field, because
 * `GET_SIGNING_CERTIFICATES` is what the resolver asks for and that is the
 * field the platform fills. A fixture using `signatures` passes nothing through
 * and the resolver correctly returns nothing.
 */
@RunWith(RobolectricTestRunner::class)
class SystemPackageIdentityTest {

    private val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
    private val identity = SystemPackageIdentity(pm)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun install(
        packageName: String,
        uid: Int,
        current: ByteArray,
        past: List<ByteArray> = emptyList(),
        coSigners: List<ByteArray> = emptyList(),
        label: String? = null,
    ) {
        val signing = SigningInfo()
        if (coSigners.isEmpty()) {
            shadowOf(signing).setSignatures(arrayOf(Signature(current)))
            if (past.isNotEmpty()) {
                shadowOf(signing).setPastSigningCertificates(
                    (past + current).map { Signature(it) }.toTypedArray(),
                )
            }
        } else {
            shadowOf(signing).setSignatures(
                (listOf(current) + coSigners).map { Signature(it) }.toTypedArray(),
            )
        }
        val info = PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                this.uid = uid
                name = label ?: packageName
            }
            signingInfo = signing
        }
        shadowOf(pm).installPackage(info)
        shadowOf(pm).setPackagesForUid(uid, packageName)
    }

    @Test
    fun anUnknownUidResolvesToNothing() {
        assertNull(identity.resolve(99_999))
    }

    @Test
    fun aUidSharedByTwoPackagesIsRefused() {
        install("com.example.one", 12_345, byteArrayOf(1))
        shadowOf(pm).setPackagesForUid(12_345, "com.example.one", "com.example.two")
        assertNull(
            "a shared UID has no single caller, so there is nothing honest to return",
            identity.resolve(12_345),
        )
    }

    @Test
    fun aResolvedCallerCarriesItsPackageName() {
        install("com.example.trailbuddy", 12_346, byteArrayOf(9, 8, 7))
        assertEquals("com.example.trailbuddy", identity.resolve(12_346)?.packageName)
    }

    @Test
    fun anAppProvesTheCertificateItIsSignedWith() {
        val cert = byteArrayOf(9, 8, 7, 6)
        install("com.example.trailbuddy", 12_347, cert)
        assertEquals(setOf(sha256(cert)), identity.digests("com.example.trailbuddy"))
    }

    @Test
    fun aRotatedAppStillProvesTheKeyItRetired() {
        // Without the retired key in the set, the rider's grant stops matching
        // and they are silently made to consent again.
        val old = byteArrayOf(1, 1)
        val current = byteArrayOf(2, 2)
        install("com.example.trailbuddy", 12_348, current, past = listOf(old))
        val proved = identity.digests("com.example.trailbuddy")
        assertTrue("the retired key must still count", sha256(old) in proved)
        assertTrue(sha256(current) in proved)
    }

    @Test
    fun aMultiplySignedAppProvesEverySignerRegardlessOfOrder() {
        // The platform does not order co-signers, so anything that picked one
        // would give a different answer between calls and revoke on a reorder.
        val a = byteArrayOf(3)
        val b = byteArrayOf(4)
        install("com.example.two", 12_349, a, coSigners = listOf(b))
        assertEquals(setOf(sha256(a), sha256(b)), identity.digests("com.example.two"))
    }

    @Test
    fun anUnknownPackageProvesNothing() {
        assertEquals(emptySet<String>(), identity.digests("com.example.absent"))
    }
}
