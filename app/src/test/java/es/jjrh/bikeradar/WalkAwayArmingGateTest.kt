// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkAwayArmingGateTest {

    @Test fun `null snapshot arms (no-LDI graceful degradation)`() {
        // Radar-only rider, no Bosch eBike. The disconnect path keeps
        // the legacy always-arm behaviour exactly.
        assertTrue(WalkAwayArmingGate.shouldArm(null))
    }

    @Test fun `snapshot with null systemLocked arms (LDI bonded but field not yet observed)`() {
        // LDI is bonded, snapshot exists, but the system_locked field
        // hasn't appeared in any NOTIFY yet. Treat the same as "no LDI
        // signal"; don't suppress arming on insufficient data.
        assertTrue(WalkAwayArmingGate.shouldArm(LiveDataSnapshot()))
    }

    @Test fun `systemLocked true arms (rider is dismounting)`() {
        // Bike is locked. Per the dismount-window memory, the rider
        // may still be at the bike removing the camera or pannier; the
        // downstream WalkAwayDecider dwell handles the window. We still
        // arm here.
        assertTrue(WalkAwayArmingGate.shouldArm(LiveDataSnapshot(systemLocked = true)))
    }

    @Test fun `systemLocked false suppresses arming (rider is riding)`() {
        // Bike is unlocked: the rider is actively riding. A radar BLE
        // blip mid-ride must not arm. This is the disarm-gate win.
        assertFalse(WalkAwayArmingGate.shouldArm(LiveDataSnapshot(systemLocked = false)))
    }

    @Test fun `flag-off path is invisible - caller never invokes us with a snapshot`() {
        // When developer.ldi.enable is false, EBikeLink is never started
        // and no snapshot ever exists. Caller passes null; we behave
        // exactly like the legacy codebase. This test pins the contract
        // mandated by the optional-accessory graceful-degradation memory
        // (both flag states must pass).
        assertTrue(WalkAwayArmingGate.shouldArm(null))
    }
}
