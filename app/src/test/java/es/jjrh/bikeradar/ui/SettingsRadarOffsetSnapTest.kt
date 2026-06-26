// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [snapOffsetCm]: the radar mount-offset slider runs on a continuous track
 * but the valid value set is `{0} ∪ {±5..±20}` - 0 (centred), then a jump to
 * ±5, then 1 cm steps out to ±20. Sub-5 cm magnitudes (±1..±4) are not valid.
 */
class SettingsRadarOffsetSnapTest {

    @Test fun `centre band snaps to zero`() {
        assertEquals(0, snapOffsetCm(0f))
        assertEquals(0, snapOffsetCm(1.4f))
        assertEquals(0, snapOffsetCm(-2f))
        // Raw is rounded to whole cm first, so 2.4 -> 2 -> centred (the 0/5
        // split lands at raw 2.5, which rounds up to 3 -> +/-5).
        assertEquals(0, snapOffsetCm(2.4f))
    }

    @Test fun `jump band snaps to the minimum magnitude`() {
        // 3,4 cm are inside the dead zone and snap up to the ±5 minimum.
        assertEquals(5, snapOffsetCm(3f))
        assertEquals(5, snapOffsetCm(4f))
        assertEquals(-5, snapOffsetCm(-3f))
        assertEquals(-5, snapOffsetCm(-4f))
    }

    @Test fun `fine region is one cm`() {
        assertEquals(5, snapOffsetCm(5f))
        assertEquals(6, snapOffsetCm(6.2f))
        assertEquals(-13, snapOffsetCm(-13f))
        assertEquals(18, snapOffsetCm(18.4f))
    }

    @Test fun `clamps to the maximum`() {
        assertEquals(20, snapOffsetCm(80f))
        assertEquals(-20, snapOffsetCm(-99f))
        assertEquals(20, snapOffsetCm(20f))
    }
}
