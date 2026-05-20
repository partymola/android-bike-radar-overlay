// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the [Prefs.eBikeOwnership] round-trip against the
 * SharedPreferences-backed Prefs store. The Settings -> eBike screen
 * branches on this enum (UNANSWERED / NO show the promotion IntentCard;
 * YES shows the toggle + status), so a regression in storage breaks
 * the screen's primary state derivation.
 *
 * Mirrors the existing [DashcamOwnership] semantics: invalid persisted
 * values fall back to [EBikeOwnership.UNANSWERED] rather than crashing.
 */
@RunWith(RobolectricTestRunner::class)
class EBikeOwnershipPrefsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPrefs() {
        // Robolectric isolates the prefs file per test; explicit clear
        // keeps the assertion-order independent of test ordering.
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun defaultIsUnanswered() {
        val prefs = Prefs(app)
        assertEquals(EBikeOwnership.UNANSWERED, prefs.eBikeOwnership)
    }

    @Test
    fun setYesPersists() {
        val prefs = Prefs(app)
        prefs.eBikeOwnership = EBikeOwnership.YES
        assertEquals(EBikeOwnership.YES, prefs.eBikeOwnership)
        // Fresh Prefs reads the same value back from SharedPreferences.
        assertEquals(EBikeOwnership.YES, Prefs(app).eBikeOwnership)
    }

    @Test
    fun setNoPersists() {
        val prefs = Prefs(app)
        prefs.eBikeOwnership = EBikeOwnership.NO
        assertEquals(EBikeOwnership.NO, prefs.eBikeOwnership)
        assertEquals(EBikeOwnership.NO, Prefs(app).eBikeOwnership)
    }

    @Test
    fun invalidStoredValueFallsBackToUnanswered() {
        // Simulate a corrupt or forward-version value left by a future
        // build. The runCatching guard in Prefs must return UNANSWERED
        // rather than throw.
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(Prefs.KEY_EBIKE_OWNERSHIP, "MAYBE")
            .apply()
        assertEquals(EBikeOwnership.UNANSWERED, Prefs(app).eBikeOwnership)
    }

    @Test
    fun roundTripAcrossAllValues() {
        val prefs = Prefs(app)
        for (value in EBikeOwnership.values()) {
            prefs.eBikeOwnership = value
            assertEquals(value, prefs.eBikeOwnership)
            // Fresh instance to confirm storage, not in-memory cache.
            assertEquals(value, Prefs(app).eBikeOwnership)
        }
    }
}
