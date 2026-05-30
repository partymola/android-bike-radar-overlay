// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [RadarDropDecider]: the radar-drop cue fires only when the radar has
 * been down past the threshold AND riding is confirmed, repeats on cadence,
 * and resets on reconnect. The eBike-data gate (`ridingConfirmed`) is what keeps
 * this cue mutually exclusive with the walk-away alarm and prevents a
 * ride-end false fire - its fail-closed cases are pinned separately below.
 */
class RadarDropDeciderTest {

    private val threshold = 60_000L
    private val cadence = 180_000L
    private val now = 10_000_000L

    @Test
    fun firesOnceThresholdReachedWhileRiding() {
        val d = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = threshold,
            ridingConfirmed = true,
            nowMs = now,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = null,
        )
        assertTrue(d.fire)
        assertEquals(now, d.lastCueMs)
        assertFalse(d.fireReconnect) // a drop fire is never also a reconnect fire
    }

    @Test
    fun doesNotFireBeforeThreshold() {
        val d = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = threshold - 1,
            ridingConfirmed = true,
            nowMs = now,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = null,
        )
        assertFalse(d.fire)
        assertNull(d.lastCueMs)
    }

    @Test
    fun doesNotFireWhenRidingNotConfirmed() {
        // ridingConfirmed=false covers all no-go eBike states the caller folds
        // in: system_locked==true (dismounting), no eBike snapshot, or a stale
        // snapshot (eBike link dropped = rider left). Well past the threshold,
        // still silent.
        val d = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = threshold * 5,
            ridingConfirmed = false,
            nowMs = now,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = null,
        )
        assertFalse(d.fire)
    }

    @Test
    fun doesNotFireWhenRadarNeverLiveThisRide() {
        val d = RadarDropDecider.decide(
            radarEverLive = false,
            radarDownForMs = threshold * 2,
            ridingConfirmed = true,
            nowMs = now,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = null,
        )
        assertFalse(d.fire)
    }

    @Test
    fun doesNotRepeatBeforeCadenceElapses() {
        val firedAt = now
        val d = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = threshold + (cadence - 1),
            ridingConfirmed = true,
            nowMs = firedAt + (cadence - 1),
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = firedAt,
        )
        assertFalse(d.fire)
        assertEquals(firedAt, d.lastCueMs) // latch preserved
    }

    @Test
    fun firesAgainOnceCadenceElapses() {
        val firedAt = now
        val d = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = threshold + cadence,
            ridingConfirmed = true,
            nowMs = firedAt + cadence,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = firedAt,
        )
        assertTrue(d.fire)
        assertEquals(firedAt + cadence, d.lastCueMs)
    }

    @Test
    fun reconnectResetsTheLatchAndFiresReconnectCue() {
        // radarDownForMs == null means the radar is back up. The latch must
        // reset so the NEXT drop fires fresh at the threshold. Because a drop
        // cue WAS raised this episode (lastCueMs non-null), the one-shot
        // reconnect cue fires on this same edge.
        val d = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = null,
            ridingConfirmed = true,
            nowMs = now,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = now - 5_000L,
        )
        assertFalse(d.fire)
        assertNull(d.lastCueMs)
        assertTrue(d.fireReconnect)
    }

    @Test
    fun coldStartReconnectIsSilent() {
        // Radar up from the start of the ride, no drop cue ever raised
        // (lastCueMs null). The reconnect cue must NOT fire - a fresh connect
        // / adb-install path should stay silent.
        val d = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = null,
            ridingConfirmed = true,
            nowMs = now,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = null,
        )
        assertFalse(d.fire)
        assertFalse(d.fireReconnect)
        assertNull(d.lastCueMs)
    }

    @Test
    fun transientBlipReconnectIsSilent() {
        // A blip shorter than the threshold never raises a drop cue, so its
        // latch stays null; when it returns, no reconnect cue fires. Modelled
        // as the two ticks the service sees: under-threshold-down, then up.
        val down = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = threshold - 1,
            ridingConfirmed = true,
            nowMs = now,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = null,
        )
        assertFalse(down.fire)
        assertNull(down.lastCueMs)
        val up = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = null,
            ridingConfirmed = true,
            nowMs = now + 1_000L,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = down.lastCueMs,
        )
        assertFalse(up.fireReconnect)
        assertNull(up.lastCueMs)
    }

    @Test
    fun reconnectCueDoesNotFireWhileStillDown() {
        // While the radar is still down (cue already raised, riding briefly
        // unconfirmed) the latch is preserved but the reconnect cue must not
        // fire - it is reserved for the actual back-up edge.
        val d = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = threshold * 3,
            ridingConfirmed = false,
            nowMs = now,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = now - 5_000L,
        )
        assertFalse(d.fireReconnect)
        assertEquals(now - 5_000L, d.lastCueMs)
    }

    @Test
    fun reconnectCueFiresOncePerDropEpisode() {
        // Two drop-reconnect cycles: the reconnect cue fires exactly once per
        // cycle. Thread the latch through the ticks the service would see.
        // Cycle 1: drop past threshold (cue fires, latch set) -> reconnect.
        val drop1 = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = threshold,
            ridingConfirmed = true,
            nowMs = now,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = null,
        )
        assertTrue(drop1.fire)
        val up1 = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = null,
            ridingConfirmed = true,
            nowMs = now + 10_000L,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = drop1.lastCueMs,
        )
        assertTrue(up1.fireReconnect)
        assertNull(up1.lastCueMs)
        // A second up tick (still no new drop) must NOT re-fire.
        val up1Again = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = null,
            ridingConfirmed = true,
            nowMs = now + 11_000L,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = up1.lastCueMs,
        )
        assertFalse(up1Again.fireReconnect)
        // Cycle 2: a fresh drop re-arms, then reconnect fires once more.
        val drop2 = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = threshold,
            ridingConfirmed = true,
            nowMs = now + 100_000L,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = up1Again.lastCueMs,
        )
        assertTrue(drop2.fire)
        val up2 = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = null,
            ridingConfirmed = true,
            nowMs = now + 110_000L,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = drop2.lastCueMs,
        )
        assertTrue(up2.fireReconnect)
    }

    @Test
    fun latchHeldWhileDownButRidingMomentarilyUnconfirmed() {
        // Down past threshold, already cued, but riding briefly unconfirmed
        // (e.g. one stale eBike tick): no fire, and the latch is preserved so a
        // re-confirm doesn't replay the cue out of cadence.
        val firedAt = now
        val d = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = threshold * 3,
            ridingConfirmed = false,
            nowMs = firedAt + 10_000L,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = firedAt,
        )
        assertFalse(d.fire)
        assertEquals(firedAt, d.lastCueMs)
    }

    private val fresh = 30_000L

    @Test
    fun ridingConfirmedTrueOnlyForAFreshUnlockedSnapshot() {
        assertTrue(RadarDropDecider.ridingConfirmed(systemLocked = false, snapshotAgeMs = 1_000L, freshMs = fresh))
        // Boundary: age == freshMs is NOT fresh (strict <).
        assertTrue(RadarDropDecider.ridingConfirmed(systemLocked = false, snapshotAgeMs = fresh - 1, freshMs = fresh))
        assertFalse(RadarDropDecider.ridingConfirmed(systemLocked = false, snapshotAgeMs = fresh, freshMs = fresh))
    }

    @Test
    fun ridingConfirmedFailsClosedWhenLockedNullOrStale() {
        // Locked (dismounting), null systemLocked, null snapshot (caller passes
        // null), and a stale snapshot (eBike link dropped) must ALL fail closed -
        // this is what stops a ride-end false fire and the walk-away collision.
        assertFalse(RadarDropDecider.ridingConfirmed(systemLocked = true, snapshotAgeMs = 1_000L, freshMs = fresh))
        assertFalse(RadarDropDecider.ridingConfirmed(systemLocked = null, snapshotAgeMs = 1_000L, freshMs = fresh))
        assertFalse(RadarDropDecider.ridingConfirmed(systemLocked = false, snapshotAgeMs = 5L * fresh, freshMs = fresh))
    }
}
