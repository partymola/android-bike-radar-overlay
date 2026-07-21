// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the manual-location persistence contract: exact [Double] round-trip via
 * raw bits, unset reads as null, and [Prefs.setManualLocation] writes or clears
 * BOTH keys in one transaction (never leaving a half-set new-lat + stale-lon
 * pair). The light auto-switch resolves its sunrise/sunset location from these,
 * so a storage regression would silently feed the wrong switch time.
 */
@RunWith(RobolectricTestRunner::class)
class ManualLocationPrefsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPrefs() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun defaultIsNull() {
        val prefs = Prefs(app)
        assertNull(prefs.manualLocationLat)
        assertNull(prefs.manualLocationLon)
    }

    @Test
    fun roundTripsExactBitsAcrossAFreshInstance() {
        Prefs(app).setManualLocation(51.5074, -0.1278)
        // Fresh instance reads from SharedPreferences, not an in-memory cache;
        // delta 0.0 asserts the exact bits survived toRawBits/fromBits.
        assertEquals(51.5074, Prefs(app).manualLocationLat!!, 0.0)
        assertEquals(-0.1278, Prefs(app).manualLocationLon!!, 0.0)
    }

    @Test
    fun roundTripsBoundaryAndZeroValues() {
        val cases = listOf(90.0 to 180.0, -90.0 to -180.0, 0.0 to 0.0, 0.000123 to -0.000456)
        for ((lat, lon) in cases) {
            Prefs(app).setManualLocation(lat, lon)
            assertEquals(lat, Prefs(app).manualLocationLat!!, 0.0)
            assertEquals(lon, Prefs(app).manualLocationLon!!, 0.0)
        }
    }

    @Test
    fun clearWithNullLatClearsBothKeys() {
        val prefs = Prefs(app)
        prefs.setManualLocation(48.85, 2.35)
        prefs.setManualLocation(null, 2.35) // a null in either argument clears both
        assertNull(Prefs(app).manualLocationLat)
        assertNull(Prefs(app).manualLocationLon)
    }

    @Test
    fun clearWithNullLonClearsBothKeys() {
        val prefs = Prefs(app)
        prefs.setManualLocation(48.85, 2.35)
        prefs.setManualLocation(48.85, null)
        assertNull(Prefs(app).manualLocationLat)
        assertNull(Prefs(app).manualLocationLon)
    }

    @Test
    fun clearWithBothNullClearsBothKeys() {
        val prefs = Prefs(app)
        prefs.setManualLocation(48.85, 2.35)
        prefs.setManualLocation(null, null)
        assertNull(Prefs(app).manualLocationLat)
        assertNull(Prefs(app).manualLocationLon)
    }
}
