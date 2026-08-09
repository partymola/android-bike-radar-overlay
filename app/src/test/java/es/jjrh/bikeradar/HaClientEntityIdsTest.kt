// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ids the Home Assistant screen offers the rider to copy.
 *
 * The families are not per-device, and a list that fanned all of them across
 * every seen device told a radar-only rider that a front-camera entity
 * existed. Over-claiming here is the failure that matters: an id that does
 * not exist gets pasted into an automation that then silently never fires.
 */
class HaClientEntityIdsTest {

    @Test fun aRadarOnlyRiderIsOfferedNoCameraEntity() {
        val ids = HaClient.publishedEntityIds(
            radarSlug = "rearvue8",
            cameraSlug = null,
            closePassEnabled = true,
        )
        assertFalse(
            "front mode is published for the camera only",
            ids.any { it.contains("front_mode") },
        )
        assertTrue(ids.contains("sensor.bikeradar_rearvue8_battery"))
        assertTrue(ids.contains("event.bikeradar_rearvue8_close_pass"))
        assertTrue(ids.contains("sensor.bikeradar_rearvue8_distance_ridden_km"))
    }

    @Test fun aCameraIsOfferedOnlyItsBatteryAndLightMode() {
        val ids = HaClient.publishedEntityIds(
            radarSlug = null,
            cameraSlug = "vue49548",
            closePassEnabled = true,
        )
        assertEquals(
            listOf(
                "sensor.bikeradar_vue49548_battery",
                "sensor.bikeradar_vue49548_front_mode",
            ),
            ids,
        )
    }

    @Test fun closePassIsOfferedOnlyWhenTheRiderHasItOn() {
        // The exact event id, not a substring: two ride-summary sensors are
        // also named close_pass_* and are published either way.
        val event = "event.bikeradar_rearvue8_close_pass"
        assertFalse(HaClient.publishedEntityIds("rearvue8", null, false).contains(event))
        assertTrue(HaClient.publishedEntityIds("rearvue8", null, true).contains(event))
    }

    @Test fun everyRideStatisticIsOfferedRatherThanOneRepresentative() {
        // Naming one of them read as "this is all you get".
        val ids = HaClient.publishedEntityIds("rearvue8", null, closePassEnabled = false)
        val rideStats = ids.filter { it.startsWith("sensor.bikeradar_rearvue8_") }
        assertEquals("battery plus the twelve ride statistics", 13, rideStats.size)
    }

    @Test fun nothingIsOfferedBeforeAnyDeviceHasBeenSeen() {
        assertEquals(emptyList<String>(), HaClient.publishedEntityIds(null, null, true))
    }
}
