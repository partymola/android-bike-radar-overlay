// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the radar-light manual-override detection. The app only ever issues
 * type-overrides (which do NOT move the radar's selected slot), so a CHANGE in
 * the reported 6a4e2f14 mode-state vs the per-connect baseline means the rider
 * moved the slot (a button press) - the override signal. Keyed on slot AND type
 * so a button press between two slots of the same type is still caught.
 */
class RadarLightOverrideDeciderTest {

    @Test fun keyFoldsSlotAndType() {
        assertEquals(0x0014, RadarLightOverrideDecider.key(0, 0x14))
        assertEquals(0x0214, RadarLightOverrideDecider.key(2, 0x14))
        // Same type, different slot -> different key (so a same-type button move is detectable).
        assertNotEquals(
            RadarLightOverrideDecider.key(0, 0x14),
            RadarLightOverrideDecider.key(1, 0x14),
        )
    }

    @Test fun nullBaselineIsNeverAnOverride() {
        assertFalse(RadarLightOverrideDecider.isOverride(null, RadarLightOverrideDecider.key(0, 0x14)))
    }

    @Test fun sameKeyIsNotAnOverride() {
        val k = RadarLightOverrideDecider.key(0, 0x14)
        assertFalse(RadarLightOverrideDecider.isOverride(k, k))
    }

    @Test fun differentKeyIsAnOverride() {
        assertTrue(
            RadarLightOverrideDecider.isOverride(
                RadarLightOverrideDecider.key(0, 0x14),
                RadarLightOverrideDecider.key(1, 0x11),
            ),
        )
        // Same type, slot moved -> still an override.
        assertTrue(
            RadarLightOverrideDecider.isOverride(
                RadarLightOverrideDecider.key(0, 0x14),
                RadarLightOverrideDecider.key(2, 0x14),
            ),
        )
    }

    @Test fun overrideClearsOnlyPastTheDeadband() {
        // Never disconnected this session.
        assertFalse(RadarLightOverrideDecider.shouldClearOverride(null, 10_000, 2_000))
        // Brief reconnect (1s < 2s deadband) keeps the override.
        assertFalse(RadarLightOverrideDecider.shouldClearOverride(9_000, 10_000, 2_000))
        // Boundary: exactly the deadband clears.
        assertTrue(RadarLightOverrideDecider.shouldClearOverride(8_000, 10_000, 2_000))
        // Long gap clears.
        assertTrue(RadarLightOverrideDecider.shouldClearOverride(5_000, 10_000, 2_000))
    }
}
