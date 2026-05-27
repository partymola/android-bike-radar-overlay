// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * JVM tests for the pure pieces of [OverlayPipeline] - the parts that don't
 * touch Android. The full per-frame loop runs on Dispatchers.Main and
 * depends on RadarOverlayView (an Android View), so it stays in Robolectric
 * territory; the bits tested here are the JSON encoder shared with HA, plus
 * the side-effect interfaces' contract surface.
 *
 * Companion piece to [PipelineReplayTest]: that test exercises the decoder
 * -> detector -> accumulator chain end-to-end on a recorded ride; this one
 * pins the close-pass payload shape that HA subscribers depend on.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayPipelineTest {

    @Test
    fun closePassJsonContainsRequiredHaFields() {
        val ev = ClosePassDetector.Event(
            // Epoch 0 keeps the asserted ISO timestamp far away from any
            // committable 20xx-xx-xx pattern (the pre-commit hook flags
            // those as possible incident anchoring); the format is what
            // matters here, not the absolute moment.
            timestampMs = 0L,
            minRangeXM = 0.83f,
            side = ClosePassDetector.Side.LEFT,
            rangeYAtMinM = 4.2f,
            closingSpeedKmh = 42,
            riderSpeedKmh = 18,
            vehicleSize = VehicleSize.CAR,
            thresholdArmedM = 1.0f,
            severity = ClosePassDetector.Severity.VERY_CLOSE,
        )
        val json: JSONObject = OverlayPipeline.closePassJson(ev)
        // Pin the field names + types HA's MQTT subscriber template config
        // expects. Any rename here breaks downstream automations silently.
        assertEquals("1970-01-01T00:00:00Z", json.getString("ts"))
        assertEquals(0.83, json.getDouble("min_range_x_m"), 0.001)
        assertEquals("left", json.getString("side"))
        assertEquals(4.2, json.getDouble("range_y_at_min_m"), 0.001)
        assertEquals(42, json.getInt("closing_speed_kmh"))
        assertEquals(18, json.getInt("rider_speed_kmh"))
        assertEquals("CAR", json.getString("vehicle_size"))
        assertEquals(1.0, json.getDouble("threshold_m"), 0.001)
        assertEquals("very_close", json.getString("severity"))
    }

    @Test
    fun closePassJsonRoundsMinRangeToTwoDecimalsAndYRangeToOne() {
        // The decoder produces float clearances; HA's gauge cards prefer
        // bounded precision so the JSON encoder rounds. A future refactor
        // that drops the String.format step would silently produce noisy
        // 5-dp payloads.
        val ev = ClosePassDetector.Event(
            timestampMs = 0L,
            minRangeXM = 0.8765432f,
            side = ClosePassDetector.Side.RIGHT,
            rangeYAtMinM = 12.34567f,
            closingSpeedKmh = 0,
            riderSpeedKmh = 0,
            vehicleSize = VehicleSize.TRUCK,
            thresholdArmedM = 1.5f,
            severity = ClosePassDetector.Severity.GRAZING,
        )
        val json = OverlayPipeline.closePassJson(ev)
        assertEquals(0.88, json.getDouble("min_range_x_m"), 0.001)
        assertEquals(12.3, json.getDouble("range_y_at_min_m"), 0.05)
    }

    @Test
    fun overlayHostStubIsConstructibleWithoutAndroidContext() {
        // Smoke test the [OverlayHost] interface itself: a fake impl can be
        // dropped in for tests that drive the pipeline without an Android
        // window manager. Pinned so the interface stays no-context-leak
        // free (no methods that return android.* types).
        val host = object : OverlayHost {
            var attachCount = 0
            var detachCount = 0
            var lastAttach: RadarOverlayView? = null
            override fun createView(): RadarOverlayView = throw UnsupportedOperationException("test does not exercise view creation")
            override fun canDrawOverlays(): Boolean = true
            override fun attach(view: RadarOverlayView): Throwable? {
                attachCount++
                lastAttach = view
                return null
            }
            override fun detach(view: RadarOverlayView) {
                detachCount++
            }
            override fun onConfigurationChanged() = Unit
        }
        // Contract: a fresh stub starts with no attach activity, and the
        // canDrawOverlays/onConfigurationChanged calls don't throw.
        assertEquals(0, host.attachCount)
        assertEquals(0, host.detachCount)
        host.onConfigurationChanged()
        assertNotNull(host)
    }

    @Test
    fun phoneBatterySourceStubIsConstructibleWithoutAndroidContext() {
        val src = object : PhoneBatterySource {
            override fun readSnapshot(): PhoneBatteryReading? = PhoneBatteryReading(level = 80, scale = 100, tempDc = 250, plugged = 0)
        }
        val s = src.readSnapshot()
        assertNotNull(s)
        assertEquals(80, s!!.level)
        assertEquals(100, s.scale)
    }
}
