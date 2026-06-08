// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Atomicity / coherency contract for the [RadarLinkState] data class wrapped in
 * a [MutableStateFlow] - the mechanism [RadarLinkCoordinator] relies on for
 * tear-free multi-field transitions. The transitions themselves (markConnected
 * / markDisconnected / tickWalkAwayState / evaluate*) are exercised against the
 * real coordinator in [RadarLinkCoordinatorTest]; this file only pins the
 * wrapper's guarantees (defaults, CAS convergence, snapshot immutability).
 */
class RadarLinkStateTest {

    @Test
    fun defaultsMatchPreOnCreateState() {
        val s = RadarLinkState()
        assertEquals(false, s.radarGattActive)
        assertNull(s.radarOffSinceMs)
        assertNull(s.radarConnectStartMs)
        assertEquals(0L, s.sessionRadarConnectedMs)
        assertEquals(false, s.walkAwayArmed)
        assertEquals(false, s.walkAwayDismissed)
        assertNull(s.lastWalkAwayFireMs)
    }

    @Test
    fun concurrentSessionTimeAccumulationDoesNotDropDeltas() {
        // The non-atomic `sessionRadarConnectedMs += delta` pattern was the
        // primary R2 race. Wrapping the integration in a CAS loop must
        // converge to the exact sum even under parallel writes.
        val flow = MutableStateFlow(RadarLinkState())
        val perWriter = 50L
        val writers = 200
        runBlocking(Dispatchers.Default) {
            repeat(writers) {
                launch {
                    flow.update { it.copy(sessionRadarConnectedMs = it.sessionRadarConnectedMs + perWriter) }
                }
            }
        }
        assertEquals(perWriter * writers, flow.value.sessionRadarConnectedMs)
    }

    @Test
    fun snapshotReadIsCoherentAcrossFields() {
        // A reader taking flow.value gets a single immutable snapshot - the
        // cluster cannot be observed half-updated. Pinned so any future
        // refactor that re-splits the cluster has to face this test.
        val flow = MutableStateFlow(RadarLinkState())
        flow.update { it.copy(radarOffSinceMs = 7_000L, walkAwayArmed = true) }
        val a = flow.value
        // Mutate after the snapshot; the snapshot is unaffected (data class
        // is immutable, copy() returns a new instance).
        flow.update { it.copy(walkAwayArmed = false) }
        assertEquals(7_000L, a.radarOffSinceMs)
        assertTrue("snapshot must remain ARMED", a.walkAwayArmed)
        assertEquals(false, flow.value.walkAwayArmed)
    }
}
