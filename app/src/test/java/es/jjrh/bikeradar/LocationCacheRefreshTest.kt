// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * [LocationCache] on a real Context: when it reads, rounds, saves and falls
 * back to the saved area, and when it deletes it. Robolectric does not model
 * Android refusing the read off screen; a refused read returns null like "no
 * fix", which is what these tests drive.
 */
// ShadowLocationManager.setLastKnownLocation mirrors the framework setter
// that Android deprecated; Robolectric 4.14 ships no typed replacement, so
// the seeding calls below suppress the deprecation at the class level.
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class LocationCacheRefreshTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val slot get() = File(app.noBackupFilesDir, LocationCache.FILE_NAME)
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        prefs = Prefs(app)
        grantLocation()
        prefs.dashcamOwnership = DashcamOwnership.YES
        prefs.dashcamMac = "AA:BB:CC:DD:EE:01"
        prefs.autoLightModeEnabled = true
        idle()
    }

    @After
    fun tearDown() {
        // LocationCache is an object singleton; clear it so state doesn't
        // leak into the next test.
        LocationCache.overrideForTest(null, null, 0L)
        slot.delete()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun grantLocation() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun denyLocation() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun fixAt(provider: String, lat: Double, lon: Double, timeMs: Long): Location = Location(provider).apply {
        latitude = lat
        longitude = lon
        time = timeMs
    }

    private fun liveFix(lat: Double, lon: Double, provider: String = LocationManager.NETWORK_PROVIDER) {
        shadowOf(lm).setLastKnownLocation(provider, fixAt(provider, lat, lon, 1_000L))
    }

    private fun noLiveFix() {
        for (p in listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)) {
            shadowOf(lm).setLastKnownLocation(p, null)
        }
    }

    private fun savedArea(): Pair<Double, Double>? {
        LocationCache.overrideForTest(null, null, 0L)
        noLiveFix()
        LocationCache.refreshIfStale(app)
        return LocationCache.current()
    }

    @Test
    fun aLiveFixIsRoundedToTenthsAndSavedInOneSlot() {
        liveFix(51.5074, -0.1278)
        assertTrue(LocationCache.refreshIfStale(app))
        assertEquals(51.5, LocationCache.current()!!.first, 1e-9)
        assertEquals(-0.1, LocationCache.current()!!.second, 1e-9)
        assertEquals("515 -1", slot.readText())
        assertTrue(LocationCache.lastFetchMs() > 0L)
    }

    @Test
    fun theSlotIsUnderTheDirectoryAndroidKeepsOutOfBackups() {
        liveFix(51.5074, -0.1278)
        LocationCache.refreshIfStale(app)
        assertTrue(slot.exists())
        assertFalse("not in the backed-up files dir", File(app.filesDir, LocationCache.FILE_NAME).exists())
    }

    @Test
    fun aSlotDeletedUnderneathIsSavedAgainOnTheNextRead() {
        // A save that failed is retried, even when the fix has not moved.
        liveFix(51.5074, -0.1278)
        LocationCache.refreshIfStale(app, maxAgeMs = 0L)
        slot.delete()
        LocationCache.refreshIfStale(app, maxAgeMs = 0L)
        assertEquals("515 -1", slot.readText())
    }

    @Test
    fun aFixThatIsNotANumberIsSkippedRatherThanCrashing() {
        liveFix(Double.NaN, -0.1278)
        assertFalse("latitude", LocationCache.refreshIfStale(app))
        liveFix(51.5074, Double.NaN)
        assertFalse("longitude", LocationCache.refreshIfStale(app))
        assertFalse(slot.exists())
    }

    @Test
    fun theFusedProviderIsRead() {
        liveFix(51.5074, -0.1278, LocationManager.FUSED_PROVIDER)
        assertTrue(LocationCache.refreshIfStale(app))
        assertEquals("515 -1", slot.readText())
    }

    @Test
    fun theSavedAreaIsUsedWhenNoLiveFixComes() {
        // The case the slot exists for: the app has since been killed, and the
        // radar connects with the app off screen, where the read is refused.
        liveFix(48.86, 2.34)
        LocationCache.refreshIfStale(app)
        val saved = savedArea()!!
        assertEquals(48.9, saved.first, 1e-9)
        assertEquals(2.3, saved.second, 1e-9)
        assertEquals("a saved area is not a live read", 0L, LocationCache.lastFetchMs())
    }

    @Test
    fun aNewerFixReplacesTheSavedOneRatherThanAddingToIt() {
        liveFix(51.5074, -0.1278)
        LocationCache.refreshIfStale(app, maxAgeMs = 0L)
        liveFix(40.4168, -3.7038)
        LocationCache.refreshIfStale(app, maxAgeMs = 0L)
        assertEquals("404 -37", slot.readText())
    }

    @Test
    fun grantedButNoFixAndNothingSavedReturnsFalse() {
        noLiveFix()
        assertFalse(LocationCache.refreshIfStale(app))
        assertNull(LocationCache.current())
        assertFalse(slot.exists())
    }

    @Test
    fun aFreshLiveAreaShortCircuitsTheRead() {
        LocationCache.overrideForTest(51.5, -0.1, System.currentTimeMillis())
        liveFix(10.0, 10.0)
        assertTrue(LocationCache.refreshIfStale(app))
        assertEquals(51.5, LocationCache.current()!!.first, 1e-9)
    }

    @Test
    fun aRevokedGrantDeletesEvenAFreshArea() {
        liveFix(51.5074, -0.1278)
        LocationCache.refreshIfStale(app)
        denyLocation()
        assertFalse(LocationCache.refreshIfStale(app))
        assertNull(LocationCache.current())
        assertFalse(slot.exists())
    }

    @Test
    fun aRevokedGrantIsCaughtAtTheNextStart() {
        // Revoking kills the process, so the start is the next chance.
        liveFix(51.5074, -0.1278)
        LocationCache.refreshIfStale(app)
        denyLocation()
        LocationCache.syncWithSettings(app)
        assertNull(LocationCache.current())
        assertFalse(slot.exists())
    }

    @Test
    fun theAppStartDeletesTheAreaOfARevokedGrant() {
        // The Application's own start, not the method it calls.
        liveFix(51.5074, -0.1278)
        LocationCache.refreshIfStale(app)
        denyLocation()
        (app as BikeRadarApp).onCreate()
        assertFalse(slot.exists())
    }

    @Test
    fun theAppStartKeepsAnAreaThatIsStillWanted() {
        // The start of a boot-launched ride is where the slot is needed most.
        liveFix(51.5074, -0.1278)
        LocationCache.refreshIfStale(app)
        (app as BikeRadarApp).onCreate()
        assertEquals("515 -1", slot.readText())
    }

    @Test
    fun aSettingChangeThatKeepsTheAreaWantedKeepsIt() {
        liveFix(51.5074, -0.1278)
        LocationCache.refreshIfStale(app)
        prefs.radarLightAutoModeEnabled = true
        idle()
        assertEquals("515 -1", slot.readText())
    }

    @Test
    fun turningAnAutoModeOnReadsTheAreaAtOnce() {
        // The rider may leave Settings without another screen resuming.
        prefs.autoLightModeEnabled = false
        idle()
        liveFix(51.5074, -0.1278)
        assertFalse(slot.exists())
        prefs.radarLightAutoModeEnabled = true
        idle()
        assertEquals("515 -1", slot.readText())
    }

    @Test
    fun turningTheRadarAutoModeOffWithTheFrontOffDeletesTheArea() {
        prefs.autoLightModeEnabled = false
        prefs.radarLightAutoModeEnabled = true
        idle()
        liveFix(51.5074, -0.1278)
        LocationCache.refreshIfStale(app)
        assertTrue(slot.exists())
        prefs.radarLightAutoModeEnabled = false
        idle()
        assertFalse(slot.exists())
    }

    @Test
    fun clearingTheFrontCameraDeletesTheAreaOfTheFrontAutoMode() {
        liveFix(51.5074, -0.1278)
        LocationCache.refreshIfStale(app)
        prefs.clearDashcamPick()
        idle()
        assertFalse(slot.exists())
    }

    @Test
    fun turningBothLightAutoModesOffDeletesTheAreaAtOnce() {
        liveFix(51.5074, -0.1278)
        LocationCache.refreshIfStale(app)
        prefs.autoLightModeEnabled = false
        idle()
        assertNull(LocationCache.current())
        assertFalse(slot.exists())
        assertFalse(LocationCache.refreshIfStale(app))
        assertFalse(slot.exists())
    }

    @Test
    fun theFrontAutoModeWithNoFrontCameraKeepsNoArea() {
        // It drives nothing without a camera, so the area would sit unused.
        liveFix(51.5074, -0.1278)
        LocationCache.refreshIfStale(app)
        prefs.dashcamOwnership = DashcamOwnership.NO
        idle()
        assertFalse(slot.exists())
        assertFalse(LocationCache.refreshIfStale(app))
    }

    @Test
    fun theRadarLightAutoModeAloneKeepsTheArea() {
        prefs.radarLightAutoModeEnabled = true
        prefs.autoLightModeEnabled = false
        idle()
        liveFix(51.5074, -0.1278)
        assertTrue(LocationCache.refreshIfStale(app))
        assertTrue(slot.exists())
    }

    @Test
    fun enteringCoordinatesDeletesTheAreaAtOnce() {
        liveFix(51.5074, -0.1278)
        LocationCache.refreshIfStale(app)
        prefs.setManualLocation(40.4, -3.7)
        idle()
        assertNull(LocationCache.current())
        assertFalse(slot.exists())
        assertFalse("coordinates win, so no area is read", LocationCache.refreshIfStale(app))
        assertFalse(slot.exists())
    }

    @Test
    fun aSlotThatDoesNotParseOrIsOutOfRangeIsDeletedAndIgnored() {
        for (bad in listOf("garbage", "901 0", "-901 0", "0 1801", "0 -1801", "515", "515 -1 7")) {
            slot.writeText(bad)
            assertNull(bad, savedArea())
            assertFalse(bad, slot.exists())
        }
    }

    @Test
    fun theRangeLimitsThemselvesLoad() {
        for ((text, lat, lon) in listOf(Triple("900 -1800", 90.0, -180.0), Triple("-900 1800", -90.0, 180.0))) {
            slot.writeText(text)
            val area = savedArea()!!
            assertEquals(text, lat, area.first, 1e-9)
            assertEquals(text, lon, area.second, 1e-9)
        }
    }

    @Test
    fun bestLastKnownPrefersTheNewestFixAcrossProviders() {
        shadowOf(lm).setLastKnownLocation(
            LocationManager.PASSIVE_PROVIDER,
            fixAt(LocationManager.PASSIVE_PROVIDER, 10.0, 10.0, 1_000L),
        )
        shadowOf(lm).setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER,
            fixAt(LocationManager.NETWORK_PROVIDER, 20.0, 20.0, 5_000L), // newest
        )
        shadowOf(lm).setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            fixAt(LocationManager.GPS_PROVIDER, 30.0, 30.0, 3_000L),
        )
        assertTrue(LocationCache.refreshIfStale(app))
        assertEquals(20.0, LocationCache.current()!!.first, 1e-9)
    }

    @Test
    fun aFixOlderThanMaxAgeTriggersARereadNotAShortCircuit() {
        // An area at or just past maxAge falls through to a live read rather
        // than short-circuiting on the stale value.
        val now = System.currentTimeMillis()
        LocationCache.overrideForTest(1.0, 1.0, now - 1_000L)
        shadowOf(lm).setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            fixAt(LocationManager.GPS_PROVIDER, 7.0, 7.0, now),
        )
        assertTrue(LocationCache.refreshIfStale(app, maxAgeMs = 1_000L))
        assertEquals(7.0, LocationCache.current()!!.first, 1e-9)
    }

    @Test
    fun aStaleAreaIsRefreshedToANewerFixWhenAvailable() {
        LocationCache.overrideForTest(1.0, 1.0, 0L)
        shadowOf(lm).setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            fixAt(LocationManager.GPS_PROVIDER, 55.94, -3.19, 9_000L),
        )
        assertTrue(LocationCache.refreshIfStale(app))
        assertEquals(55.9, LocationCache.current()!!.first, 1e-9)
    }
}
