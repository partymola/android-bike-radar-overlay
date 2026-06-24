// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EBikeCaptureFormatterTest {

    @Test fun `empty snapshot renders null (skip logging)`() {
        assertNull(EBikeCaptureFormatter.format(LiveDataSnapshot(), sessionStartOdometerM = null))
    }

    @Test fun `populated snapshot renders all observed fields`() {
        val snap = LiveDataSnapshot(
            speedRaw = 1080,
            cadence = 85,
            riderPower = 120,
            batterySoc = 80,
            systemLocked = true,
            chargerConnected = false,
            bikeNotDriving = false,
        )
        val line = EBikeCaptureFormatter.format(snap, sessionStartOdometerM = null)
        assertNotNull(line)
        assertEquals(
            "ebike spd_raw=1080 cad=85 power=120 batt=80 sysl=1 chg=0 notdrv=0",
            line,
        )
    }

    @Test fun `null fields are omitted from the line`() {
        val snap = LiveDataSnapshot(speedRaw = 500)
        val line = EBikeCaptureFormatter.format(snap, sessionStartOdometerM = null)
        assertEquals("ebike spd_raw=500", line)
    }

    @Test fun `odometer renders as delta-since-baseline, never absolute`() {
        // Session starts when the first odometer reading is observed.
        // Caller passes that as the baseline; later snapshots log only
        // the delta. The absolute is rider-identifying and must not
        // appear in the log.
        val snap = LiveDataSnapshot(odometerM = 1_000_500L)
        val baseline = 1_000_000L
        val line = EBikeCaptureFormatter.format(snap, sessionStartOdometerM = baseline)
        assertEquals("ebike odo_delta_m=500", line)
        // Hard negative: the absolute must NOT appear in the line.
        assertTrue("absolute odometer must not leak", !line!!.contains("1000500"))
        assertTrue("baseline must not leak", !line.contains("1000000"))
    }

    @Test fun `odometer with null baseline uses current as zero-delta first sighting`() {
        // First-ever sighting of odometer in this session. Caller hasn't
        // captured a baseline yet, so the delta is rendered as zero.
        // Subsequent calls will pass the captured baseline.
        val snap = LiveDataSnapshot(odometerM = 1_000_500L)
        val line = EBikeCaptureFormatter.format(snap, sessionStartOdometerM = null)
        assertEquals("ebike odo_delta_m=0", line)
    }

    @Test fun `time renders as ISO-8601 from seconds-since-epoch`() {
        val snap = LiveDataSnapshot(timeSec = 1_747_600_000L)
        val line = EBikeCaptureFormatter.format(snap, sessionStartOdometerM = null)
        assertNotNull(line)
        // Don't pin the exact rendered timestamp (Instant.toString format
        // is stable but the actual value depends on JDK behaviour); just
        // confirm the t_iso prefix is present and is not a raw integer.
        assertTrue("expected ISO-8601 t_iso prefix: $line", line!!.contains("t_iso="))
        assertTrue("raw epoch seconds must not appear: $line", !line.contains("1747600000"))
        assertTrue("ISO 'T' separator must be present", line.contains("T"))
        assertTrue("ISO 'Z' suffix must be present", line.contains("Z"))
    }

    @Test fun `motor power assist mode and wheel circumference render with distinct keys`() {
        val snap = LiveDataSnapshot(
            motorPower = 250,
            assistMode = 2,
            wheelCircumferenceMm = 2200,
        )
        val line = EBikeCaptureFormatter.format(snap, sessionStartOdometerM = null)
        assertEquals("ebike mpower=250 assist=2 wheel_mm=2200", line)
    }

    @Test fun `bike_light enum value preserved as integer`() {
        // bikeLight is binary 0=off/1=on on the proprietary stream; the log
        // preserves the raw value (the 0..3 loop guards the passthrough against
        // any future encoding) so a consumer's mapping can change without a re-ride.
        for (v in 0..3) {
            val line = EBikeCaptureFormatter.format(
                LiveDataSnapshot(bikeLight = v),
                sessionStartOdometerM = null,
            )
            assertEquals("ebike blight=$v", line)
        }
    }

    @Test fun `all boolean flags true encode as 1, with raw brightness`() {
        val snap = LiveDataSnapshot(
            ambientBrightnessRaw = 42,
            systemLocked = true,
            chargerConnected = true,
            lightReserve = true,
            diagnosisActive = true,
            bikeNotDriving = true,
        )
        val line = EBikeCaptureFormatter.format(snap, sessionStartOdometerM = null)
        assertEquals("ebike lux_raw=42 sysl=1 chg=1 lreserve=1 diag=1 notdrv=1", line)
    }

    @Test fun `all boolean flags false encode as 0`() {
        val snap = LiveDataSnapshot(
            systemLocked = false,
            chargerConnected = false,
            lightReserve = false,
            diagnosisActive = false,
            bikeNotDriving = false,
        )
        val line = EBikeCaptureFormatter.format(snap, sessionStartOdometerM = null)
        assertEquals("ebike sysl=0 chg=0 lreserve=0 diag=0 notdrv=0", line)
    }
}
