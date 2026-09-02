// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import org.junit.Assert.assertFalse
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
}
