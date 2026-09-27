// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.access

import android.app.Activity
import es.jjrh.bikeradar.ipc.RadarContract.Consent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        override fun recordKeyCheck(packageName: String, certDigest: String, proven: Boolean) {
            items[packageName]?.takeIf { it.certDigest == certDigest }?.let { items[packageName] = it.copy(refused = !proven) }
        }
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

    /**
     * The gate refuses a grant stored under a key this app cannot prove, so
     * showing it as current would tell the rider this app already has access.
     */
    @Test
    fun aGrantUnderAKeyTheAppCannotProveIsNotShownAsCurrent() {
        store.put(RadarGrant(PKG, "zz99", "Trail Buddy", 1L, 2L, read = true, control = true))
        assertEquals(ConsentRequest.Ask(PKG, "Trail Buddy", null), decider().open(PKG))
    }

    /** Recorded here, while the app is asking, because Settings cannot look it up later. */
    @Test
    fun askingUnderAKeyTheAppCannotProveRecordsTheRefusal() {
        store.put(RadarGrant(PKG, "zz99", "Trail Buddy", 1L, 2L, read = true, control = true))
        decider().open(PKG)
        assertTrue(store.grantFor(PKG)!!.refused)
    }

    /** "aa11" is the app's lowest key but not the first the fixture lists, so only membership passes. */
    @Test
    fun askingUnderAProvableKeyRecordsNothing() {
        store.put(RadarGrant(PKG, "aa11", "Trail Buddy", 1L, 2L, read = true, control = false))
        decider().open(PKG)
        assertFalse(store.grantFor(PKG)!!.refused)
    }

    /** Proven now, so "couldn't confirm" is no longer true even if the rider then cancels. */
    @Test
    fun askingUnderAProvableKeyClearsARecordedRefusal() {
        store.put(RadarGrant(PKG, "aa11", "Trail Buddy", 1L, 2L, read = true, control = false, refused = true))
        val asked = decider().open(PKG) as ConsentRequest.Ask
        assertFalse(store.grantFor(PKG)!!.refused)
        assertFalse("the question carries the grant as it now stands", asked.current!!.refused)
    }

    @Test
    fun aRideInProgressRecordsNothing() {
        store.put(RadarGrant(PKG, "zz99", "Trail Buddy", 1L, 2L, read = true, control = true))
        decider(riding = true).open(PKG)
        assertFalse(store.grantFor(PKG)!!.refused)
    }

    @Test
    fun anUnreadableSignatureRecordsNothing() {
        store.put(RadarGrant(PKG, "zz99", "Trail Buddy", 1L, 2L, read = true, control = true))
        decider(FakeIdentity(certs = emptySet())).open(PKG)
        assertFalse(store.grantFor(PKG)!!.refused)
    }

    @Test
    fun anAnswerReplacesARecordedRefusal() {
        store.put(RadarGrant(PKG, "zz99", "Trail Buddy", 1L, 2L, read = true, control = true, refused = true))
        decider().decide(PKG, "Trail Buddy", read = true, control = false)
        assertFalse(store.grantFor(PKG)!!.refused)
    }

    /** Any of the app's keys will do, as at the gate: not only the one a grant stores today. */
    @Test
    fun aGrantUnderAnotherOfTheAppsKeysIsShownAsCurrent() {
        store.put(RadarGrant(PKG, "bb22", "Trail Buddy", 1L, 2L, read = true, control = false))
        assertEquals(true, (decider().open(PKG) as ConsentRequest.Ask).current?.read)
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

    /**
     * Settings shows when an app last used the radar, and "Not used yet" for
     * zero. Saving the same app's grant again, changed or not, must not make
     * an app in daily use read as never used.
     */
    @Test
    fun savingAGrantAgainKeepsWhenTheAppLastUsedTheRadar() {
        store.put(RadarGrant(PKG, "aa11", "Trail Buddy", 1L, 3_000L, read = true, control = false))
        decider().decide(PKG, "Trail Buddy", read = true, control = true)
        assertEquals(3_000L, store.grantFor(PKG)!!.lastUsedAtMs)
    }

    /** Stored under the app's other key, as after a signer rotation: still this app. */
    @Test
    fun aGrantUnderAnotherOfTheAppsKeysKeepsItsLastUse() {
        store.put(RadarGrant(PKG, "bb22", "Trail Buddy", 1L, 3_000L, read = true, control = false))
        decider().decide(PKG, "Trail Buddy", read = true, control = true)
        assertEquals(3_000L, store.grantFor(PKG)!!.lastUsedAtMs)
    }

    /** A grant under a key the app cannot prove is not carried over, so its record starts fresh. */
    @Test
    fun aGrantForANewSigningKeyStartsUnused() {
        store.put(RadarGrant(PKG, "zz99", "Trail Buddy", 1L, 3_000L, read = true, control = false))
        decider().decide(PKG, "Trail Buddy", read = true, control = false)
        assertEquals(0L, store.grantFor(PKG)!!.lastUsedAtMs)
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
