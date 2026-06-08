// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the front-camera override predicates: a device mode that differs from the
 * last mode we wrote is a manual side-button press, and a session override
 * clears only once the link has been down past the deadband. Parity with
 * [RadarLightOverrideDeciderTest], for the dashcam's distinct "device !=
 * lastWritten" signal.
 */
class CameraLightOverrideDeciderTest {

    @Test fun isOverride_nullExpected_isNeverAnOverride() {
        // Before we have written any mode this connect, the first device-reported
        // mode must not be mistaken for a rider override.
        assertFalse(CameraLightOverrideDecider.isOverride(null, CameraLightMode.DAY_FLASH))
    }

    @Test fun isOverride_sameAsWritten_isNotAnOverride() {
        assertFalse(CameraLightOverrideDecider.isOverride(CameraLightMode.DAY_FLASH, CameraLightMode.DAY_FLASH))
    }

    @Test fun isOverride_differsFromWritten_isAnOverride() {
        assertTrue(CameraLightOverrideDecider.isOverride(CameraLightMode.DAY_FLASH, CameraLightMode.MEDIUM))
    }

    @Test fun shouldClearOverride_nullOffSince_neverClears() {
        assertFalse(CameraLightOverrideDecider.shouldClearOverride(offSinceMs = null, nowMs = 1_000_000, deadbandMs = DEADBAND))
    }

    @Test fun shouldClearOverride_belowDeadband_keepsOverride() {
        // 1s down, 2s deadband -> a blip; keep the rider's manual choice.
        assertFalse(CameraLightOverrideDecider.shouldClearOverride(offSinceMs = 0L, nowMs = 1_000L, deadbandMs = 2_000L))
    }

    @Test fun shouldClearOverride_atDeadband_clears() {
        // Boundary: elapsed == deadband must clear (the >= boundary).
        assertTrue(CameraLightOverrideDecider.shouldClearOverride(offSinceMs = 0L, nowMs = 2_000L, deadbandMs = 2_000L))
    }

    @Test fun shouldClearOverride_pastDeadband_clears() {
        assertTrue(CameraLightOverrideDecider.shouldClearOverride(offSinceMs = 0L, nowMs = 5_000L, deadbandMs = 2_000L))
    }

    private companion object {
        const val DEADBAND = 120_000L
    }
}
