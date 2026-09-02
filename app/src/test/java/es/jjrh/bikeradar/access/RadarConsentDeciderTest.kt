// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import android.app.Activity
import es.jjrh.bikeradar.ipc.RadarContract.Consent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who the consent screen will ask about, and what an answer does to the store. */
class RadarConsentDeciderTest {

    private class FakeStore(private val refuseWrites: Boolean = false) : RadarGrantStore {
        val items = mutableMapOf<String, RadarGrant>()

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

        override fun markUsed(packageName: String, atMs: Long) = Unit
    }

    private class FakeIdentity(
        private val installed: Map<String, Int> = mapOf(PKG to 42),
        private val owners: Map<Int, String?> = mapOf(42 to PKG),
        private val certs: Set<String> = setOf("bb22", "aa11"),
    ) : PackageIdentity {
        override fun resolve(uid: Int) = owners[uid]?.let { CallerIdentity(it, "Trail Buddy") }

        override fun digests(packageName: String) = if (packageName in installed) certs else emptySet()

        override fun uidOf(packageName: String) = installed[packageName]
    }

    private companion object {
        const val PKG = "com.example.trailbuddy"
    }

    private val store = FakeStore()

    private fun decider(
        identity: PackageIdentity = FakeIdentity(),
        riding: Boolean = false,
    ) = RadarConsentDecider(store, identity, rideInProgress = { riding }, now = { 5_000L })

    @Test
    fun aCallerThatDidNotStartThisForAResultCannotBeIdentified() {
        assertEquals(
            ConsentRequest.Refuse(Consent.RESULT_CALLER_UNKNOWN),
            decider().open(null),
        )
        assertEquals("nothing may be stored for a caller with no name", 0, store.items.size)
    }

    @Test
    fun anAppThatIsNotInstalledIsRefused() {
        assertEquals(
            ConsentRequest.Refuse(Consent.RESULT_CALLER_UNKNOWN),
            decider(FakeIdentity(installed = emptyMap())).open(PKG),
        )
    }

    @Test
    fun aCallerSharingItsUidIsRefused() {
        // The gate refuses a shared UID, so a grant made here could never be
        // used. Refusing now says so rather than leaving a dead grant behind.
        val shared = FakeIdentity(installed = mapOf(PKG to 42), owners = mapOf(42 to null))
        assertEquals(
            ConsentRequest.Refuse(Consent.RESULT_CALLER_UNKNOWN),
            decider(shared).open(PKG),
        )
    }

    @Test
    fun anAppWhoseSignatureCannotBeReadIsRefused() {
        val unreadable = FakeIdentity(certs = emptySet())
        assertEquals(
            ConsentRequest.Refuse(Consent.RESULT_CALLER_UNKNOWN),
            decider(unreadable).open(PKG),
        )
    }

    @Test
    fun aRideInProgressIsRefusedAsRetryable() {
        assertEquals(
            ConsentRequest.Refuse(Consent.RESULT_RIDE_IN_PROGRESS),
            decider(riding = true).open(PKG),
        )
        assertEquals("a refusal mid-ride stores nothing", 0, store.items.size)
    }

    @Test
    fun anIdentifiedAppIsAskedAboutAndNothingIsStoredYet() {
        val asked = decider().open(PKG)
        assertEquals(ConsentRequest.Ask(PKG, "Trail Buddy", null), asked)
        assertEquals("opening the screen is not consent", 0, store.items.size)
    }

    @Test
    fun anAppThatWasAlreadyGrantedIsAskedAboutWithItsCurrentAnswer() {
        store.put(RadarGrant(PKG, "aa11", "Trail Buddy", 1L, 2L, read = true, control = false))
        val asked = decider().open(PKG) as ConsentRequest.Ask
        assertEquals(true, asked.current?.read)
        assertEquals(false, asked.current?.control)
    }

    @Test
    fun approvingReadAloneDoesNotGrantControl() {
        assertEquals(Activity.RESULT_OK, decider().decide(PKG, "Trail Buddy", read = true, control = false))
        val stored = store.grantFor(PKG)!!
        assertTrue(stored.read)
        assertEquals(false, stored.control)
    }

    @Test
    fun approvingBothGrantsBoth() {
        decider().decide(PKG, "Trail Buddy", read = true, control = true)
        val stored = store.grantFor(PKG)!!
        assertTrue(stored.read)
        assertTrue(stored.control)
    }

    @Test
    fun approvingNeitherRemovesAnyExistingGrant() {
        store.put(RadarGrant(PKG, "aa11", "Trail Buddy", 1L, 2L, read = true, control = true))
        assertEquals(Activity.RESULT_OK, decider().decide(PKG, "Trail Buddy", read = false, control = false))
        assertNull("answering no is the same state as never having said yes", store.grantFor(PKG))
    }

    @Test
    fun aStoredGrantCarriesAKeyTheAppCanProveToday() {
        decider().decide(PKG, "Trail Buddy", read = true, control = false)
        assertTrue(
            "the gate checks this against the app's keys on every call",
            store.grantFor(PKG)!!.certDigest in FakeIdentity().digests(PKG),
        )
    }

    @Test
    fun theStoredKeyIsTheSameOnASecondGrantOfTheSameApp() {
        // The app has more than one signer, and the platform does not order
        // them, so picking one arbitrarily would change the stored value
        // between grants and refuse the app after the next one.
        decider().decide(PKG, "Trail Buddy", read = true, control = false)
        val first = store.grantFor(PKG)!!.certDigest
        decider().decide(PKG, "Trail Buddy", read = true, control = true)
        assertEquals(first, store.grantFor(PKG)!!.certDigest)
        assertEquals("re-approving replaces rather than adds", 1, store.items.size)
    }

    @Test
    fun anAnswerThatCouldNotBeSavedIsNotReportedAsGranted() {
        // A store too damaged to read refuses every write. Returning OK there
        // would tell the app it had standing access while every later call
        // denied, and re-granting would hit the same refusal.
        val refusing = RadarConsentDecider(
            FakeStore(refuseWrites = true),
            FakeIdentity(),
            rideInProgress = { false },
            now = { 5_000L },
        )
        assertEquals(
            Consent.RESULT_NOT_STORED,
            refusing.decide(PKG, "Trail Buddy", read = true, control = false),
        )
        assertEquals(
            "revoking through the screen must report the same way",
            Consent.RESULT_NOT_STORED,
            refusing.decide(PKG, "Trail Buddy", read = false, control = false),
        )
    }

    @Test
    fun theStoredKeyIsTheLowestTheAppCanProve() {
        // Pinned as a literal: the fixture set iterates bb22 first, so a
        // first-or-last pick passes the stability test while storing a
        // different key than the one this claims.
        decider().decide(PKG, "Trail Buddy", read = true, control = false)
        assertEquals("aa11", store.grantFor(PKG)!!.certDigest)
    }

    @Test
    fun anAppThatCannotProveAKeyIsNotGranted() {
        val unreadable = FakeIdentity(certs = emptySet())
        assertEquals(
            Consent.RESULT_CALLER_UNKNOWN,
            decider(unreadable).decide(PKG, "Trail Buddy", read = true, control = true),
        )
        assertNull(store.grantFor(PKG))
    }
}
