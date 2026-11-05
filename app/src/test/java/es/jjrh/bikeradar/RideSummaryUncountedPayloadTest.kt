// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Home Assistant gets no close-pass figures for a ride where nothing was counted. */
@RunWith(RobolectricTestRunner::class)
class RideSummaryUncountedPayloadTest {

    private val closePassKeys = listOf("close_pass_count", "grazing_count", "hgv_close_pass_count", "close_pass_conversion_rate")

    private fun snapshot(
        counted: Boolean,
        overtakes: Int = 12,
        closePasses: Int = 3,
        grazing: Int = 2,
        hgv: Int = 1,
        ratePct: Float = 37.5f,
    ) = RideStatsSnapshot(
        overtakesTotal = overtakes,
        closePassCount = closePasses,
        grazingCount = grazing,
        hgvClosePassCount = hgv,
        peakClosingKmh = 40,
        closingSpeedP90Kmh = 35,
        minLateralClearanceM = 0.8f,
        distanceRiddenKm = 7.4f,
        exposureSeconds = 300L,
        closePassConversionRatePct = ratePct,
        tightestPass = null,
        rideStartedAtMs = 700_000_000_000L,
        alertsPerKm = 1.5f,
        alertsPerHourOfRide = 12.5f,
        closePassesCounted = counted,
    )

    @Test fun anUncountedRideSendsNoClosePassFiguresAndEverythingElse() {
        val payload = HaClient("", "").buildRideSummaryState(snapshot(counted = false))
        for (f in closePassKeys) assertFalse(f, payload.has(f))
        assertEquals(12, payload.getInt("overtakes_total"))
        assertEquals(7.4, payload.getDouble("distance_ridden_km"), 1e-4)
        assertEquals(300, payload.getInt("exposure_seconds"))
        assertEquals(40, payload.getInt("peak_closing_kmh"))
        assertEquals(35, payload.getInt("closing_speed_p90_kmh"))
        assertEquals(0.8, payload.getDouble("min_lateral_clearance_m"), 1e-4)
        assertEquals(1.5, payload.getDouble("alerts_per_km"), 1e-4)
        assertEquals(12.5, payload.getDouble("alerts_per_hour_of_ride"), 1e-4)
        assertEquals("1992-03-07T20:26:40Z", payload.getString("ride_started_at"))
    }

    @Test fun aCountedRideSendsEachFigureUnderItsOwnKey() {
        val payload = HaClient("", "").buildRideSummaryState(snapshot(counted = true))
        assertEquals(3, payload.getInt("close_pass_count"))
        assertEquals(2, payload.getInt("grazing_count"))
        assertEquals(1, payload.getInt("hgv_close_pass_count"))
        assertEquals(37.5, payload.getDouble("close_pass_conversion_rate"), 0.0)
    }

    @Test fun aCountedRideWithNoPassesSendsZerosNotUnknown() {
        val payload = HaClient("", "").buildRideSummaryState(
            snapshot(counted = true, overtakes = 0, closePasses = 0, grazing = 0, hgv = 0, ratePct = 0f),
        )
        for (f in closePassKeys) {
            assertTrue(f, payload.has(f))
            assertEquals(f, 0.0, payload.getDouble(f), 0.0)
        }
    }

    /** A sensor reads Unknown only because its template names a key the payload left out. */
    @Test fun everySensorTemplateReadsAKeyTheStatePayloadWrites() {
        val client = HaClient("", "")
        val state = client.buildRideSummaryState(snapshot(counted = true))
        val field = Regex("""value_json\.(\w+) """)
        for ((_, discovery) in client.buildRideSummaryDiscoveryPayloads("radar", "Radar")) {
            val key = field.find(discovery.getString("value_template"))!!.groupValues[1]
            assertTrue(key, state.has(key))
            assertEquals("bikeradar_radar_$key", discovery.getString("object_id"))
        }
    }
}
