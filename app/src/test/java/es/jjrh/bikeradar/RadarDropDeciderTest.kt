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
    fun reconnectResetsTheLatch() {
        // radarDownForMs == null means the radar is back up. The latch must
        // reset so the NEXT drop fires fresh at the threshold.
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
