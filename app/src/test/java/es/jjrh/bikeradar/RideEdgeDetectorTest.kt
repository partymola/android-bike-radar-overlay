// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideEdgeDetectorTest {

    private fun snap(locked: Boolean? = null, notDriving: Boolean? = null) = LiveDataSnapshot(systemLocked = locked, bikeNotDriving = notDriving)

    @Test fun `first snapshot never synthesises an edge`() {
        // Cold start: rider's app launches with the bike already locked
        // and parked. We must not publish a spurious "ended" event from
        // the no-state initial transition.
        val (next, edge) = RideEdgeDetector.next(
            RideEdgeDetector.State(),
            snap(locked = true, notDriving = true),
        )
        assertEquals(RideEdgeDetector.Edge.NONE, edge)
        assertFalse(next.isRiding)
        assertTrue(next.firstSnapshotSeen)
    }

    @Test fun `unlock plus wheel turning fires STARTED`() {
        // Typical morning: rider unlocks the bike (lock false), then
        // pushes off (wheel turning). The unlock alone is not enough;
        // STARTED fires when both conditions hold.
        val warm = RideEdgeDetector.State(isRiding = false, firstSnapshotSeen = true)
        val (afterUnlock, e1) = RideEdgeDetector.next(warm, snap(locked = false, notDriving = true))
        assertEquals(RideEdgeDetector.Edge.NONE, e1)
        assertFalse(afterUnlock.isRiding)
        val (afterPush, e2) = RideEdgeDetector.next(afterUnlock, snap(locked = false, notDriving = false))
        assertEquals(RideEdgeDetector.Edge.STARTED, e2)
        assertTrue(afterPush.isRiding)
    }

    @Test fun `lock fires ENDED`() {
        val riding = RideEdgeDetector.State(isRiding = true, firstSnapshotSeen = true)
        val (after, edge) = RideEdgeDetector.next(riding, snap(locked = true, notDriving = true))
        assertEquals(RideEdgeDetector.Edge.ENDED, edge)
        assertFalse(after.isRiding)
    }

    @Test fun `traffic-light stop stays RIDING (no spurious ENDED)`() {
        // Rider stops at a light: wheel at rest, bike still unlocked.
        // This is the canyon-drift GPS-edge problem we are deliberately
        // killing. State stays "riding"; no edge fires.
        val riding = RideEdgeDetector.State(isRiding = true, firstSnapshotSeen = true)
        val (after, edge) = RideEdgeDetector.next(riding, snap(locked = false, notDriving = true))
        assertEquals(RideEdgeDetector.Edge.NONE, edge)
        assertTrue(after.isRiding)
    }

    @Test fun `unlock without wheel motion stays PARKED`() {
        // Rider unlocks the bike but hasn't pushed off yet (still
        // mounting). No STARTED edge until the wheel actually turns.
        val parked = RideEdgeDetector.State(isRiding = false, firstSnapshotSeen = true)
        val (after, edge) = RideEdgeDetector.next(parked, snap(locked = false, notDriving = true))
        assertEquals(RideEdgeDetector.Edge.NONE, edge)
        assertFalse(after.isRiding)
    }

    @Test fun `partial snapshot - only systemLocked - still resolves`() {
        // Field-presence merge means the snapshot can carry only one
        // signal at a time. A locked-only snapshot mid-ride must still
        // fire ENDED.
        val riding = RideEdgeDetector.State(isRiding = true, firstSnapshotSeen = true)
        val (after, edge) = RideEdgeDetector.next(riding, snap(locked = true))
        assertEquals(RideEdgeDetector.Edge.ENDED, edge)
        assertFalse(after.isRiding)
    }

    @Test fun `both signals null - state and edge unchanged`() {
        // No-LDI rider, or a snapshot before the first relevant NOTIFY.
        // No basis for any edge decision.
        val warm = RideEdgeDetector.State(isRiding = true, firstSnapshotSeen = true)
        val (after, edge) = RideEdgeDetector.next(warm, LiveDataSnapshot())
        assertEquals(RideEdgeDetector.Edge.NONE, edge)
        assertEquals(warm, after)
    }

    @Test fun `flag-off path - caller never invokes us - graceful degrade`() {
        // When developer.ldi.enable is false, no snapshots arrive and
        // RideEdgeDetector is never invoked. No HA events fire. This
        // test pins the no-invocation contract by confirming the empty
        // initial state is itself stable across a noop snapshot.
        val empty = RideEdgeDetector.State()
        val (after, edge) = RideEdgeDetector.next(empty, LiveDataSnapshot())
        assertEquals(RideEdgeDetector.Edge.NONE, edge)
        assertEquals(empty, after)
    }

    @Test fun `full ride sequence - start, two light stops, end`() {
        var s = RideEdgeDetector.State()
        // Cold start (locked at the rack).
        s = RideEdgeDetector.next(s, snap(locked = true, notDriving = true)).first
        // Rider unlocks.
        var (next, edge) = RideEdgeDetector.next(s, snap(locked = false, notDriving = true))
        assertEquals(RideEdgeDetector.Edge.NONE, edge)
        s = next
        // Rider pushes off.
        RideEdgeDetector.next(s, snap(locked = false, notDriving = false)).also {
            assertEquals(RideEdgeDetector.Edge.STARTED, it.second)
            s = it.first
        }
        // First traffic light.
        RideEdgeDetector.next(s, snap(locked = false, notDriving = true)).also {
            assertEquals(RideEdgeDetector.Edge.NONE, it.second)
            s = it.first
        }
        // Resumes.
        RideEdgeDetector.next(s, snap(locked = false, notDriving = false)).also {
            assertEquals(RideEdgeDetector.Edge.NONE, it.second)
            s = it.first
        }
        // Second traffic light.
        RideEdgeDetector.next(s, snap(locked = false, notDriving = true)).also {
            assertEquals(RideEdgeDetector.Edge.NONE, it.second)
            s = it.first
        }
        // Resumes again.
        RideEdgeDetector.next(s, snap(locked = false, notDriving = false)).also {
            assertEquals(RideEdgeDetector.Edge.NONE, it.second)
            s = it.first
        }
        // Rider locks the bike at the office.
        RideEdgeDetector.next(s, snap(locked = true, notDriving = true)).also {
            assertEquals(RideEdgeDetector.Edge.ENDED, it.second)
            s = it.first
        }
        assertFalse(s.isRiding)
    }
}
