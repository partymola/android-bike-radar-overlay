// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What survives a write, and what a damaged store does rather than lose grants. */
@RunWith(RobolectricTestRunner::class)
class PrefsRadarGrantStoreTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: PrefsRadarGrantStore

    private fun grant(
        pkg: String = "com.example.trailbuddy",
        read: Boolean = true,
        control: Boolean = false,
        lastUsedAtMs: Long = 0L,
    ) = RadarGrant(pkg, "aa11", "Trail Buddy", 1_700L, lastUsedAtMs, read, control)

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("radar-access-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = PrefsRadarGrantStore(prefs)
    }

    @Test
    fun anEmptyStoreGrantsNothing() {
        assertEquals(emptyList<RadarGrant>(), store.all())
        assertNull(store.grantFor("com.example.trailbuddy"))
    }

    @Test
    fun everyFieldSurvivesAWriteAndRead() {
        val g = RadarGrant("com.example.trailbuddy", "ff00", "Trail Buddy", 42L, 7L, read = true, control = true)
        store.put(g)
        assertEquals(g, store.grantFor("com.example.trailbuddy"))
    }

    @Test
    fun bothFlagsSurviveInBothStates() {
        store.put(grant(read = true, control = false))
        assertTrue(store.grantFor("com.example.trailbuddy")!!.read)
        assertEquals(false, store.grantFor("com.example.trailbuddy")!!.control)
        store.put(grant(read = false, control = true))
        assertEquals(false, store.grantFor("com.example.trailbuddy")!!.read)
        assertTrue(store.grantFor("com.example.trailbuddy")!!.control)
    }

    @Test
    fun puttingTheSamePackageTwiceReplacesRatherThanDuplicates() {
        store.put(grant(control = false))
        store.put(grant(control = true))
        assertEquals(1, store.all().size)
        assertTrue(store.grantFor("com.example.trailbuddy")!!.control)
    }

    @Test
    fun severalAppsAreHeldIndependently() {
        store.put(grant(pkg = "com.example.trailbuddy"))
        store.put(grant(pkg = "com.example.other", read = false, control = true))
        assertEquals(2, store.all().size)
        assertTrue(store.grantFor("com.example.trailbuddy")!!.read)
        assertTrue(store.grantFor("com.example.other")!!.control)
    }

    @Test
    fun revokingRemovesOnlyThatApp() {
        store.put(grant(pkg = "com.example.trailbuddy"))
        store.put(grant(pkg = "com.example.other"))
        store.revoke("com.example.trailbuddy")
        assertNull(store.grantFor("com.example.trailbuddy"))
        assertEquals(1, store.all().size)
    }

    @Test
    fun markUsedStampsOnlyThatApp() {
        store.put(grant(pkg = "com.example.trailbuddy"))
        store.put(grant(pkg = "com.example.other"))
        store.markUsed("com.example.trailbuddy", 500_000L)
        assertEquals(500_000L, store.grantFor("com.example.trailbuddy")!!.lastUsedAtMs)
        assertEquals(0L, store.grantFor("com.example.other")!!.lastUsedAtMs)
    }

    @Test
    fun markUsedOnAnUnknownAppCreatesNothing() {
        store.markUsed("com.example.ghost", 500_000L)
        assertEquals(emptyList<RadarGrant>(), store.all())
    }

    @Test
    fun aStampWithinTheResolutionWindowIsNotWritten() {
        // Otherwise this is a whole-file rewrite per allowed call, and an
        // allowed call is a radar frame.
        store.put(grant(lastUsedAtMs = 500_000L))
        store.markUsed("com.example.trailbuddy", 500_100L)
        assertEquals(500_000L, store.grantFor("com.example.trailbuddy")!!.lastUsedAtMs)
        store.markUsed("com.example.trailbuddy", 561_000L)
        assertEquals(561_000L, store.grantFor("com.example.trailbuddy")!!.lastUsedAtMs)
    }

    @Test
    fun aDamagedStoreReadsAsNothingGrantedRatherThanThrowing() {
        prefs.edit().putString("radar_access_grants", "{not json at all").commit()
        assertEquals("a parse failure must deny, not crash a binder call", emptyList<RadarGrant>(), store.all())
        assertNull(store.grantFor("com.example.trailbuddy"))
    }

    @Test
    fun oneBrokenEntryDeniesTheEntriesBeforeItToo() {
        // A partial read would hand back the first grant out of a store known
        // to be corrupt. Fail closed on the whole set instead.
        prefs.edit().putString(
            "radar_access_grants",
            """[{"pkg":"com.example.trailbuddy","cert":"aa11","read":true},{"pkg":"com.example.broken"}]""",
        ).commit()
        assertEquals(emptyList<RadarGrant>(), store.all())
        assertNull(store.grantFor("com.example.trailbuddy"))
    }

    @Test
    fun aWriteAgainstADamagedStoreRefusesRatherThanDiscardingWhatItCannotRead() {
        // Every write rewrites the whole array, so proceeding from an empty
        // read would silently destroy every grant the rider still holds.
        val damaged = """[{"pkg":"com.example.trailbuddy","cert":"aa11"},{"pkg":"com.example.broken"}]"""
        prefs.edit().putString("radar_access_grants", damaged).commit()

        store.put(grant(pkg = "com.example.new"))
        assertEquals("the damaged value must survive a refused write", damaged, prefs.getString("radar_access_grants", null))

        store.revoke("com.example.trailbuddy")
        assertEquals(damaged, prefs.getString("radar_access_grants", null))

        store.markUsed("com.example.trailbuddy", 900_000L)
        assertEquals(damaged, prefs.getString("radar_access_grants", null))
    }

    @Test
    fun aRevokeIsNotUndoneByAConcurrentStamp() {
        // The binder thread stamps while the settings screen revokes. Both read
        // the whole array and write it back, so a stamp that read before the
        // revoke would write the revoked app back.
        //
        // Forced rather than raced: a plain two-thread version never lost, so it
        // would have passed with no lock at all. The stamping thread is held
        // between its read and its write, the revoke is run while it waits, and
        // only then is it let go.
        store.put(grant(pkg = "com.example.trailbuddy", lastUsedAtMs = 0L))
        store.put(grant(pkg = "com.example.other"))

        val hasRead = CountDownLatch(1)
        val mayWrite = CountDownLatch(1)
        val held = PrefsRadarGrantStore(
            HoldsTheFirstRead(prefs, hasRead, mayWrite),
        )

        val stamping = Thread { held.markUsed("com.example.trailbuddy", 900_000L) }
        stamping.start()
        assertTrue("the stamping thread never reached its read", hasRead.await(5, TimeUnit.SECONDS))

        val revoking = Thread { store.revoke("com.example.trailbuddy") }
        revoking.start()
        mayWrite.countDown()
        stamping.join(5_000)
        revoking.join(5_000)

        assertNull("a revoked grant must not be resurrected", store.grantFor("com.example.trailbuddy"))
        assertNotNull(store.grantFor("com.example.other"))
    }

    /** Pauses the first read so a second writer can be run inside that window. */
    private class HoldsTheFirstRead(
        private val delegate: SharedPreferences,
        private val hasRead: CountDownLatch,
        private val mayWrite: CountDownLatch,
    ) : SharedPreferences by delegate {
        private var paused = false

        override fun getString(key: String?, defValue: String?): String? {
            val value = delegate.getString(key, defValue)
            if (!paused) {
                paused = true
                hasRead.countDown()
                mayWrite.await(5, TimeUnit.SECONDS)
            }
            return value
        }
    }
}
