// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkAwayArmingGateTest {

    private val fresh = 30_000L

    @Test fun `null snapshot arms (no-eBike graceful degradation)`() {
        // Radar-only rider, no Bosch eBike. The disconnect path keeps
        // the legacy always-arm behaviour exactly.
        assertTrue(WalkAwayArmingGate.shouldArm(null, snapshotAgeMs = 0L, freshMs = fresh))
    }

    @Test fun `snapshot with null systemLocked arms (eBike bonded but field not yet observed)`() {
        // eBike is bonded, snapshot exists, but the system_locked field
        // hasn't appeared in any NOTIFY yet. Treat the same as "no eBike
        // signal"; don't suppress arming on insufficient data.
        assertTrue(WalkAwayArmingGate.shouldArm(LiveDataSnapshot(), snapshotAgeMs = 0L, freshMs = fresh))
    }

    @Test fun `systemLocked true arms (rider is dismounting)`() {
        // Bike is locked. Per the dismount-window memory, the rider
        // may still be at the bike removing the camera or pannier; the
        // downstream WalkAwayDecider dwell handles the window. We still
        // arm here.
        assertTrue(WalkAwayArmingGate.shouldArm(LiveDataSnapshot(systemLocked = true), snapshotAgeMs = 0L, freshMs = fresh))
    }

    @Test fun `fresh systemLocked false suppresses arming (rider is riding)`() {
        // Bike unlocked AND the reading is fresh: the rider is actively
        // riding, so a radar BLE blip mid-ride must not arm - the disarm gate
        // wins. Also pins the fresh edge of the window (age = freshMs - 1).
        assertFalse(WalkAwayArmingGate.shouldArm(LiveDataSnapshot(systemLocked = false), snapshotAgeMs = 0L, freshMs = fresh))
        assertFalse(WalkAwayArmingGate.shouldArm(LiveDataSnapshot(systemLocked = false), snapshotAgeMs = fresh - 1, freshMs = fresh))
    }

    @Test fun `stale systemLocked false arms (eBike link dropped before parking)`() {
        // The eBike link dropped mid-ride and the snapshot froze at unlocked;
        // by the time the rider parks and walks off that "false" is stale and
        // must NOT suppress the walk-away (dashcam-left) reminder. Fail toward
        // arming, mirroring RadarDropDecider.ridingConfirmed's stale contract.
        // The window is half-open (age < freshMs is fresh), so age == freshMs
        // is already stale.
        assertTrue(WalkAwayArmingGate.shouldArm(LiveDataSnapshot(systemLocked = false), snapshotAgeMs = fresh, freshMs = fresh))
        assertTrue(WalkAwayArmingGate.shouldArm(LiveDataSnapshot(systemLocked = false), snapshotAgeMs = fresh + 1, freshMs = fresh))
    }

    @Test fun `flag-off path is invisible - caller never invokes us with a snapshot`() {
        // When the eBike feature is off, the status reader is never started
        // and no snapshot ever exists. Caller passes null; we behave
        // exactly like the legacy codebase. This test pins the contract
        // mandated by the optional-accessory graceful-degradation memory
        // (both flag states must pass).
        assertTrue(WalkAwayArmingGate.shouldArm(null, snapshotAgeMs = 0L, freshMs = fresh))
    }
}
