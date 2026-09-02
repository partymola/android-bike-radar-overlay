// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who the gate lets through, and what it does to a grant that no longer fits. */
class StoredRadarAccessGateTest {

    private class FakeStore(vararg grants: RadarGrant, private val refuseWrites: Boolean = false) : RadarGrantStore {
        val items = grants.associateBy { it.packageName }.toMutableMap()

        override fun grantFor(packageName: String) = items[packageName]

        override fun all() = items.values.toList()

        override fun put(grant: RadarGrant): Boolean {
            if (refuseWrites) return false
            items[grant.packageName] = grant
            return true
        }

        override fun revoke(packageName: String): Boolean {
            if (refuseWrites) return false
            items.remove(packageName)
            return true
        }

        override fun markUsed(packageName: String, atMs: Long) {
            items[packageName]?.let { items[packageName] = it.copy(lastUsedAtMs = atMs) }
        }
    }

    /**
     * Keyed by package on purpose. A fake answering the same set whatever it
     * was asked would leave the gate free to check the wrong app's keys, and
     * that check is the only thing between a grant and an app reinstalled under
     * the same name from somewhere else.
     */
    private class FakeIdentity(
        private val byUid: Map<Int, CallerIdentity>,
        private val certsByPackage: Map<String, Set<String>> = mapOf(PKG to setOf(CERT)),
    ) : PackageIdentity {
        override fun resolve(uid: Int) = byUid[uid]

        override fun digests(packageName: String) = certsByPackage[packageName].orEmpty()

        override fun uidOf(packageName: String) = byUid.entries.firstOrNull { it.value.packageName == packageName }?.key
    }

    @Test
    fun aCallerIsAnsweredFromItsOwnGrantAndNotWhicheverIsFirst() {
        // Every other fixture holds one grant, so a lookup ignoring the caller
        // and taking the first entry passes all of them, and hands a stranger
        // whatever the first app was allowed.
        val other = CallerIdentity("com.example.other", "Other")
        val store = FakeStore(
            RadarGrant(PKG, CERT, "Trail Buddy", 1L, 0L, read = true, control = false),
            RadarGrant("com.example.other", "cc33", "Other", 1L, 0L, read = true, control = true),
        )
        val identity = FakeIdentity(
            byUid = mapOf(42 to caller, 43 to other, 44 to CallerIdentity("com.example.stranger", "Stranger")),
            certsByPackage = mapOf(PKG to setOf(CERT), "com.example.other" to setOf("cc33")),
        )
        val g = gate(store, identity)
        assertTrue(g.canRead(42))
        assertFalse("the app was never granted control", g.canControl(42))
        assertTrue(g.canControl(43))
        assertFalse("a stranger holds no grant, whoever else does", g.canRead(44))
    }

    @Test
    fun theCertificatesCheckedAreTheCallersOwn() {
        val store = FakeStore(grant(read = true))
        val identity = FakeIdentity(
            byUid = mapOf(42 to caller),
            certsByPackage = mapOf(PKG to setOf("bb22"), "es.jjrh.bikeradar" to setOf(CERT)),
        )
        assertFalse(
            "the app cannot prove the granted key, whatever another package proves",
            gate(store, identity).canRead(42),
        )
    }

    private companion object {
        const val PKG = "com.example.trailbuddy"
        const val CERT = "aa11"
    }

    private val caller = CallerIdentity(PKG, "Trail Buddy")

    private fun grant(read: Boolean = true, control: Boolean = false, cert: String = CERT) = RadarGrant(PKG, cert, "Trail Buddy", 1L, 0L, read, control)

    private fun gate(
        store: RadarGrantStore,
        identity: PackageIdentity = FakeIdentity(mapOf(42 to caller)),
    ) = StoredRadarAccessGate(store, identity, now = { 999_999L })

    @Test
    fun aCallerWithNoGrantGetsNothing() {
        val g = gate(FakeStore())
        assertFalse(g.canRead(42))
        assertFalse(g.canControl(42))
    }

    @Test
    fun aUidThatResolvesToNoPackageIsRefusedWithoutTouchingTheGrant() {
        val store = FakeStore(grant(read = true, control = true))
        val g = gate(store, FakeIdentity(emptyMap()))
        assertFalse(g.canRead(42))
        assertFalse(g.canControl(42))
        assertNotNull(
            "an unidentifiable caller is refused, never a reason to revoke",
            store.grantFor(PKG),
        )
    }

    @Test
    fun readDoesNotImplyControl() {
        val g = gate(FakeStore(grant(read = true, control = false)))
        assertTrue(g.canRead(42))
        assertFalse("granting the stream must not grant the tail light", g.canControl(42))
    }

    @Test
    fun controlDoesNotImplyRead() {
        val g = gate(FakeStore(grant(read = false, control = true)))
        assertTrue(g.canControl(42))
        assertFalse(g.canRead(42))
    }

    @Test
    fun aCertificateTheAppCannotProveIsRefusedAndTheGrantSurvives() {
        val store = FakeStore(grant(read = true, control = true, cert = "not-this-app"))
        assertFalse(gate(store).canRead(42))
        assertNotNull(
            "the rider re-consents rather than losing a grant to an unreadable signature",
            store.grantFor(PKG),
        )
    }

    @Test
    fun aRotatedKeyStillCountsAsTheAppTheRiderApproved() {
        // The app now signs with a new key, but still proves it owns the old
        // one. Without this the rider is silently made to consent again.
        val store = FakeStore(grant(read = true, cert = "retired-key"))
        val rotated = FakeIdentity(mapOf(42 to caller), certsByPackage = mapOf(PKG to setOf("retired-key", "current-key")))
        assertTrue(gate(store, rotated).canRead(42))
    }

    @Test
    fun anAppThatProvesNoCertificateAtAllIsRefused() {
        val g = gate(FakeStore(grant(read = true)), FakeIdentity(mapOf(42 to caller), certsByPackage = emptyMap()))
        assertFalse(g.canRead(42))
    }

    @Test
    fun anAllowedCallStampsLastUsed() {
        val store = FakeStore(grant(read = true))
        assertTrue(gate(store).canRead(42))
        assertEquals(999_999L, store.grantFor(PKG)!!.lastUsedAtMs)
    }

    @Test
    fun aRefusedCallDoesNotStampLastUsed() {
        val store = FakeStore(grant(read = true, control = false))
        assertFalse(gate(store).canControl(42))
        assertEquals("a refusal is not a use", 0L, store.grantFor(PKG)!!.lastUsedAtMs)
    }

    @Test
    fun revokingTakesEffectOnTheNextCall() {
        val store = FakeStore(grant(read = true))
        val g = gate(store)
        assertTrue(g.canRead(42))
        store.revoke(PKG)
        assertFalse("no decision may be cached across a revoke", g.canRead(42))
    }
}
