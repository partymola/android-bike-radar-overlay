// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The placeholder gate must refuse everything.
 *
 * Without this a permissive stub ships an ungated radar stream while every
 * other check stays green, which is the one way this surface fails silently.
 */
class RadarAccessGateStubTest {

    @Test
    fun theStubGrantsNothingToAnyCaller() {
        for (uid in listOf(0, 1000, 10_042, Int.MAX_VALUE, -1)) {
            assertFalse("read must be refused for uid $uid", DeniedAccessGate.canRead(uid))
            assertFalse("control must be refused for uid $uid", DeniedAccessGate.canControl(uid))
        }
    }

    @Test
    fun theTwoConsentRefusalsAreDistinguishable() {
        assertNotEquals(
            "a consumer retries one and not the other",
            RadarConsent.RESULT_RIDE_IN_PROGRESS,
            RadarConsent.RESULT_CALLER_UNKNOWN,
        )
    }

    @Test
    fun theConsentResultCodesAreTheValuesAConsumerHardcodes() {
        // A separately built app cannot import these, so it copies the numbers.
        // Changing one silently breaks a consumer that is already shipping.
        assertEquals(1, RadarConsent.RESULT_RIDE_IN_PROGRESS)
        assertEquals(2, RadarConsent.RESULT_CALLER_UNKNOWN)
        assertEquals("es.jjrh.bikeradar.action.REQUEST_RADAR_ACCESS", RadarConsent.ACTION)
    }
}
