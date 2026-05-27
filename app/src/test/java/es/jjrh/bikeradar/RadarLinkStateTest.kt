// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Atomicity / coherency contract for [RadarLinkState]. The data class itself
 * is plain Kotlin; the contract under test is that wrapping it in a
 * [MutableStateFlow] gives us multi-field transitions that readers cannot
 * tear (the seven-`@Volatile`-field cluster could).
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
    fun connectThenDisconnectIntegratesSessionTime() {
        // Mirrors the markRadarConnected / markRadarDisconnected pair on the
        // service. radarConnectStartMs is the integration anchor; on the next
        // disconnect, sessionRadarConnectedMs accumulates the elapsed delta
        // and the anchor is cleared.
        val flow = MutableStateFlow(RadarLinkState())
        flow.update { it.copy(radarConnectStartMs = 1_000L, radarGattActive = true) }
        flow.update { current ->
            val added = current.radarConnectStartMs?.let { 4_000L - it } ?: 0L
            current.copy(
                radarGattActive = false,
                radarConnectStartMs = null,
                sessionRadarConnectedMs = current.sessionRadarConnectedMs + added,
                radarOffSinceMs = current.radarOffSinceMs ?: 4_000L,
            )
        }
        val s = flow.value
        assertEquals(false, s.radarGattActive)
        assertNull(s.radarConnectStartMs)
        assertEquals(3_000L, s.sessionRadarConnectedMs)
        assertEquals(4_000L, s.radarOffSinceMs)
    }

    @Test
    fun midEpisodeDisconnectStutterDoesNotRefreshOffInstant() {
        // The walk-away threshold is measured from the FIRST disconnect of
        // the off-episode. A radar stutter mid-episode must not slide the
        // off-instant forward; otherwise the alarm would keep getting
        // pushed back.
        val flow = MutableStateFlow(RadarLinkState(radarOffSinceMs = 1_000L, walkAwayArmed = true))
        flow.update { current ->
            current.copy(radarOffSinceMs = current.radarOffSinceMs ?: 99_999L)
        }
        assertEquals(1_000L, flow.value.radarOffSinceMs)
    }

    @Test
    fun reconnectClearsWalkAwayFieldsTogether() {
        // The any -> IDLE transition resets the walk-away machine. Readers
        // must never see a half-cleared cluster (radarOffSinceMs cleared but
        // walkAwayArmed still true would mis-fire the decider).
        val flow = MutableStateFlow(
            RadarLinkState(
                radarOffSinceMs = 1_000L,
                walkAwayArmed = true,
                walkAwayDismissed = true,
                lastWalkAwayFireMs = 2_500L,
                radarGattActive = false,
            ),
        )
        flow.update { current ->
            if (current.radarOffSinceMs != null) {
                current.copy(
                    radarOffSinceMs = null,
                    walkAwayArmed = false,
                    walkAwayDismissed = false,
                    lastWalkAwayFireMs = null,
                    radarConnectStartMs = 5_000L,
                    radarGattActive = true,
                )
            } else {
                current.copy(radarConnectStartMs = 5_000L, radarGattActive = true)
            }
        }
        val s = flow.value
        assertNull(s.radarOffSinceMs)
        assertEquals(false, s.walkAwayArmed)
        assertEquals(false, s.walkAwayDismissed)
        assertNull(s.lastWalkAwayFireMs)
        assertNotNull(s.radarConnectStartMs)
        assertEquals(true, s.radarGattActive)
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
