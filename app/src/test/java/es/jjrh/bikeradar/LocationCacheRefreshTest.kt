// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric cover for [LocationCache.refreshIfStale] / `bestLastKnown` -
 * the permission gate, the staleness short-circuit, and the
 * newest-fix-wins selection across providers. [LocationCacheTest] pins the
 * pure state machine; this drives the Android-context read path that picks
 * the fix the SunsetCalculator runs on. A wrong gate here silently sends
 * front-light auto-mode back to the London fallback every ride.
 */
// ShadowLocationManager.setLastKnownLocation mirrors the framework setter
// that Android deprecated; Robolectric 4.14 ships no typed replacement, so
// the seeding calls below suppress the deprecation at the class level.
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class LocationCacheRefreshTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @After
    fun tearDown() {
        // LocationCache is an object singleton; clear it so state doesn't
        // leak into the next test.
        LocationCache.overrideForTest(null, null, 0L)
    }

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

    @Test
    fun freshCacheShortCircuitsWithoutTouchingLocationServices() {
        denyLocation() // would block a real read; the short-circuit must not reach it
        LocationCache.overrideForTest(51.5, -0.1, System.currentTimeMillis())
        assertTrue(LocationCache.refreshIfStale(app))
        // Cache value is unchanged by the short-circuit.
        assertEquals(51.5, LocationCache.current()!!.first, 1e-6)
    }

    @Test
    fun deniedPermissionLeavesAnEmptyCacheEmptyAndReportsFalse() {
        denyLocation()
        LocationCache.overrideForTest(null, null, 0L)
        assertFalse(LocationCache.refreshIfStale(app))
        assertNull(LocationCache.current())
    }

    @Test
    fun deniedPermissionKeepsAStaleCacheAndReportsItsPresence() {
        denyLocation()
        // Stale (cachedAtMs = 0) so the staleness short-circuit does not fire;
        // the permission gate is what returns, and it returns "have a value".
        LocationCache.overrideForTest(40.4, -3.7, 0L)
        assertTrue(LocationCache.refreshIfStale(app))
        assertEquals(40.4, LocationCache.current()!!.first, 1e-6)
    }

    @Test
    fun grantedButNoProviderFixReturnsFalseOnAnEmptyCache() {
        grantLocation()
        LocationCache.overrideForTest(null, null, 0L)
        assertFalse(LocationCache.refreshIfStale(app))
        assertNull(LocationCache.current())
    }

    @Test
    fun grantedWithAFixPopulatesTheCache() {
        grantLocation()
        LocationCache.overrideForTest(null, null, 0L)
        shadowOf(lm).setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER,
            fixAt(LocationManager.NETWORK_PROVIDER, 48.85, 2.35, 1_000L),
        )
        assertTrue(LocationCache.refreshIfStale(app))
        val current = LocationCache.current()!!
        assertEquals(48.85, current.first, 1e-6)
        assertEquals(2.35, current.second, 1e-6)
        assertTrue(LocationCache.lastFetchMs() > 0L)
    }

    @Test
    fun bestLastKnownPrefersTheNewestFixAcrossProviders() {
        grantLocation()
        LocationCache.overrideForTest(null, null, 0L)
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
        assertEquals(20.0, LocationCache.current()!!.first, 1e-6)
    }

    @Test
    fun aFixOlderThanMaxAgeTriggersARereadNotAShortCircuit() {
        // Pins the `now - cachedAtMs < maxAgeMs` comparison: with a custom
        // small maxAge, a cache entry at/just past that age must fall through
        // to a live provider read rather than short-circuit on the stale value.
        grantLocation()
        val now = System.currentTimeMillis()
        LocationCache.overrideForTest(1.0, 1.0, now - 1_000L)
        shadowOf(lm).setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            fixAt(LocationManager.GPS_PROVIDER, 7.0, 7.0, now),
        )
        assertTrue(LocationCache.refreshIfStale(app, maxAgeMs = 1_000L))
        assertEquals(7.0, LocationCache.current()!!.first, 1e-6)
    }

    @Test
    fun staleCacheIsRefreshedToANewerFixWhenAvailable() {
        grantLocation()
        // Existing stale fix (cachedAtMs = 0) so the staleness branch lets the
        // read proceed and overwrite with the live provider fix.
        LocationCache.overrideForTest(1.0, 1.0, 0L)
        shadowOf(lm).setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            fixAt(LocationManager.GPS_PROVIDER, 55.95, -3.19, 9_000L),
        )
        assertTrue(LocationCache.refreshIfStale(app))
        assertEquals(55.95, LocationCache.current()!!.first, 1e-6)
    }
}
