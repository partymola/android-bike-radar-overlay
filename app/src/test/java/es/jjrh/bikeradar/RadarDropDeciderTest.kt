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
        assertTrue(riding(systemLocked = false, snapshotAgeMs = 1_000L))
        // Boundary: age == freshMs is NOT fresh (strict <).
        assertTrue(riding(systemLocked = false, snapshotAgeMs = fresh - 1))
        assertFalse(riding(systemLocked = false, snapshotAgeMs = fresh))
    }

    @Test
    fun ridingConfirmedFailsClosedWhenLockedNullOrStale() {
        // Locked (dismounting), null systemLocked, null snapshot (caller passes
        // null), and a stale snapshot (eBike link dropped) must ALL fail closed -
        // this is what stops a ride-end false fire and the walk-away collision.
        // (radarActivityFreshAtDrop defaults false: eBike-only, as the callers
        // that omit it behave.)
        assertFalse(riding(systemLocked = true, snapshotAgeMs = 1_000L))
        assertFalse(riding(systemLocked = null, snapshotAgeMs = 1_000L))
        assertFalse(riding(systemLocked = false, snapshotAgeMs = 5L * fresh))
    }

    @Test
    fun ridingConfirmedTrueViaRadarActivityWhenEBikeGateClosed() {
        // The radar-only path: the eBike gate is closed (no snapshot / stale)
        // but the radar-activity latch is true -> riding confirmed. This is
        // the OR that reaches the F-Droid, no-eBike rider.
        assertTrue(
            RadarDropDecider.ridingConfirmed(
                systemLocked = null, // no eBike
                snapshotAgeMs = 5L * fresh, // irrelevant
                freshMs = fresh,
                eBikeRidingFresh = true,
                radarActivityFreshAtDrop = true,
            ),
        )
        // A stale-unlocked snapshot doesn't confirm on its own, but the
        // latch still does (a mid-ride Flow dropout must not mute the cue).
        assertTrue(
            RadarDropDecider.ridingConfirmed(
                systemLocked = false,
                snapshotAgeMs = 5L * fresh,
                freshMs = fresh,
                eBikeRidingFresh = true,
                radarActivityFreshAtDrop = true,
            ),
        )
        // Both gates closed -> false.
        assertFalse(
            RadarDropDecider.ridingConfirmed(
                systemLocked = null,
                snapshotAgeMs = 5L * fresh,
                freshMs = fresh,
                eBikeRidingFresh = true,
                radarActivityFreshAtDrop = false,
            ),
        )
    }

    @Test
    fun ridingConfirmedLockedVetoOverridesTheRadarActivityLatch() {
        // The observed field bug: rider parks and locks within the latch's
        // freshness window of still moving; the latch stays true for the whole
        // off-episode and the cue repeated against a parked, locked bike. A
        // last-known locked bike means the rider explicitly parked, so it must
        // veto BOTH confirmation paths - and it is sticky (a riding rider is
        // never last-known-locked, so staleness cannot mute a genuine drop).
        assertFalse(
            RadarDropDecider.ridingConfirmed(
                systemLocked = true,
                snapshotAgeMs = 1_000L,
                freshMs = fresh,
                eBikeRidingFresh = true,
                radarActivityFreshAtDrop = true,
            ),
        )
        // Sticky: still vetoes when the locked reading has aged out.
        assertFalse(
            RadarDropDecider.ridingConfirmed(
                systemLocked = true,
                snapshotAgeMs = 5L * fresh,
                freshMs = fresh,
                eBikeRidingFresh = true,
                radarActivityFreshAtDrop = true,
            ),
        )
    }

    @Test
    fun ridingConfirmedParkedVetoAlsoTakesTheRidersOwnDeclaration() {
        // The second source of the same veto, and the only one a rider without
        // an eBike has. It must veto HERE rather than only at the banner: the
        // cue's repeat cap is reset for a parked ride, so a declaration that
        // reaches the reset without reaching this predicate uncaps the cue and
        // it fires every tick. Measured at 6 cues where 0 was expected before
        // this argument existed.
        assertFalse(
            RadarDropDecider.ridingConfirmed(
                systemLocked = null, // no eBike, so the latch is the only confirmation
                snapshotAgeMs = 1_000L,
                freshMs = fresh,
                eBikeRidingFresh = true,
                radarActivityFreshAtDrop = true,
                riderEndedRide = true,
            ),
        )
        // And the other direction, so the veto cannot widen into always-off.
        assertTrue(
            RadarDropDecider.ridingConfirmed(
                systemLocked = null,
                snapshotAgeMs = 1_000L,
                freshMs = fresh,
                eBikeRidingFresh = true,
                radarActivityFreshAtDrop = true,
                riderEndedRide = false,
            ),
        )
    }

    /** The eBike confirmation path with the speed gate SATISFIED - i.e. what the
     *  old two-argument gate meant. Keeps the pre-existing cases readable while
     *  the garage cases below vary the speed term explicitly. */
    private fun riding(systemLocked: Boolean?, snapshotAgeMs: Long, eBikeRidingFresh: Boolean = true) = RadarDropDecider.ridingConfirmed(
        systemLocked = systemLocked,
        snapshotAgeMs = snapshotAgeMs,
        freshMs = fresh,
        eBikeRidingFresh = eBikeRidingFresh,
    )

    @Test
    fun eBikePathNeedsSpeedNotJustAnUnlockedBike() {
        // The garage/office bug: an eBike reports itself unlocked the moment it
        // is switched on, so the unlock bit alone confirmed "riding" while the
        // rider stood indoors with the radar still in a pannier - and the cue
        // fired on cadence until the radar came up. A fresh, unlocked snapshot
        // with no recent sustained ride must NOT confirm.
        assertFalse(riding(systemLocked = false, snapshotAgeMs = 1_000L, eBikeRidingFresh = false))
        // ...and the same snapshot WITH a recent ride still does (a genuine
        // mid-ride drop must never be silenced by this gate).
        assertTrue(riding(systemLocked = false, snapshotAgeMs = 1_000L, eBikeRidingFresh = true))
    }

    @Test
    fun radarOnlyPathIsUnaffectedByTheEBikeSpeedTerm() {
        // The no-eBike (F-Droid) rider's latch is an independent OR: it must
        // still confirm when the eBike speed term is false, because there is no
        // eBike to report speed in the first place.
        assertTrue(
            RadarDropDecider.ridingConfirmed(
                systemLocked = null,
                snapshotAgeMs = 5L * fresh,
                freshMs = fresh,
                eBikeRidingFresh = false,
                radarActivityFreshAtDrop = true,
            ),
        )
    }

    @Test
    fun latchOnlyConfirmationStopsAfterTheCueCap() {
        // Latch-only confirmation (no live eBike signal) can never be
        // un-confirmed, so it is capped: after MAX_LATCH_ONLY_CUES cues in one
        // off-episode the decider goes silent even though riding still reads
        // as confirmed. Without the cap a quick park beeps forever.
        var lastCue: Long? = null
        var count = 0
        var t = now
        repeat(RadarDropDecider.MAX_LATCH_ONLY_CUES) {
            val d = RadarDropDecider.decide(
                radarEverLive = true,
                radarDownForMs = threshold + (t - now),
                ridingConfirmed = true,
                nowMs = t,
                thresholdMs = threshold,
                cadenceMs = cadence,
                lastCueMs = lastCue,
                latchOnlyConfirmation = true,
                cueCount = count,
            )
            assertTrue("cue ${it + 1} of the cap must still fire", d.fire)
            lastCue = d.lastCueMs
            count = d.cueCount
            t += cadence
        }
        // Literal, not the constant: reading MAX_LATCH_ONLY_CUES here would let
        // it drop to 0 (cue suppressed from the first firing - a dead radar
        // reading as all-clear) with this test still green. The literal makes a
        // change to the cap break the test deliberately.
        assertEquals(3, count)
        val after = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = threshold + (t - now),
            ridingConfirmed = true,
            nowMs = t,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = lastCue,
            latchOnlyConfirmation = true,
            cueCount = count,
        )
        assertFalse("cue past the latch-only cap must stay silent", after.fire)
        assertEquals("count must not grow past the cap", count, after.cueCount)
    }

    @Test
    fun liveEBikeConfirmationIsNotCapped() {
        // A live eBike "unlocked" genuinely re-confirms riding every tick, so
        // it keeps repeating past the latch-only cap.
        val d = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = threshold + 10 * cadence,
            ridingConfirmed = true,
            nowMs = now + 10 * cadence,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = now,
            latchOnlyConfirmation = false,
            cueCount = RadarDropDecider.MAX_LATCH_ONLY_CUES + 5,
        )
        assertTrue("live-confirmed repeats must not be capped", d.fire)
    }

    @Test
    fun reconnectResetsTheCueCount() {
        val d = RadarDropDecider.decide(
            radarEverLive = true,
            radarDownForMs = null, // back up
            ridingConfirmed = true,
            nowMs = now,
            thresholdMs = threshold,
            cadenceMs = cadence,
            lastCueMs = now - 5_000L,
            latchOnlyConfirmation = true,
            cueCount = RadarDropDecider.MAX_LATCH_ONLY_CUES,
        )
        assertEquals("a reconnect must reset the per-episode cue count", 0, d.cueCount)
        assertTrue(d.fireReconnect)
    }

    @Test
    fun isRidingActivityTrueOnlyAboveWalkingPace() {
        val pace = 2.0f
        assertTrue(RadarDropDecider.isRidingActivity(bikeSpeedMs = 2.5f, walkingPaceMs = pace))
        // Boundary: exactly at the pace is NOT riding (strict >).
        assertFalse(RadarDropDecider.isRidingActivity(bikeSpeedMs = pace, walkingPaceMs = pace))
        assertFalse(RadarDropDecider.isRidingActivity(bikeSpeedMs = 1.9f, walkingPaceMs = pace))
        // Null speed (no device-status frame / speed-less radar) is not riding.
        assertFalse(RadarDropDecider.isRidingActivity(bikeSpeedMs = null, walkingPaceMs = pace))
    }

    @Test
    fun activityFreshAtDropRespectsWindowAndFailsClosedOnNull() {
        val window = 30_000L
        val drop = 1_000_000L
        assertTrue(RadarDropDecider.activityFreshAtDrop(drop, lastActivityMs = drop - 1_000L, windowMs = window))
        // Boundary: age == window is NOT fresh (strict <).
        assertTrue(RadarDropDecider.activityFreshAtDrop(drop, lastActivityMs = drop - (window - 1), windowMs = window))
        assertFalse(RadarDropDecider.activityFreshAtDrop(drop, lastActivityMs = drop - window, windowMs = window))
        // No riding activity this session -> fail closed.
        assertFalse(RadarDropDecider.activityFreshAtDrop(drop, lastActivityMs = null, windowMs = window))
    }

    // ── track-presence fallback (range-only radar, no eBike) ─────────────────

    @Test
    fun trackActivityCountsOnlyOnASourceThatCannotReportRiderSpeed() {
        // The whole confinement of the fallback. V2 reports rider speed, so its
        // frames keep the measured speed gate and must never stamp this latch,
        // however much traffic is behind the rider.
        assertTrue(RadarDropDecider.isTrackActivity(DataSource.V1, vehiclesPresent = true))
        assertFalse(RadarDropDecider.isTrackActivity(DataSource.V2, vehiclesPresent = true))
        // A clear road is not activity on any source.
        assertFalse(RadarDropDecider.isTrackActivity(DataSource.V1, vehiclesPresent = false))
        // NONE is the synthetic / replay source, not a radar: a demo scenario
        // must not leave a latch a later live drop reads.
        assertFalse(RadarDropDecider.isTrackActivity(DataSource.NONE, vehiclesPresent = true))
    }

    @Test
    fun trackFallbackIsWithdrawnFromAnEBikeRider() {
        // The rider's toggle is deliberately not a parameter here: the cue
        // applies it per tick and the wakelock does not apply it at all, so it
        // is the callers' policy. The eBike gate is this function's own, and it
        // is what stops the proxy reaching a cohort whose bike answers the
        // riding question and carries a locked veto the proxy cannot.
        val window = 30_000L
        val drop = 1_000_000L
        val seen = drop - 1_000L
        assertTrue(
            "no eBike, track seen inside the window -> confirmed",
            RadarDropDecider.trackActivityFreshAtDrop(drop, seen, window, hasEBikeSignal = false),
        )
        assertFalse(
            "an eBike answers the riding question, and brings a locked veto this proxy has no equivalent of",
            RadarDropDecider.trackActivityFreshAtDrop(drop, seen, window, hasEBikeSignal = true),
        )
    }

    @Test
    fun trackFallbackRespectsTheWindowAndFailsClosedOnNull() {
        val window = 30_000L
        val drop = 1_000_000L
        fun open(lastTrackMs: Long?) = RadarDropDecider.trackActivityFreshAtDrop(
            drop,
            lastTrackMs,
            window,
            hasEBikeSignal = false,
        )
        assertTrue(open(drop - (window - 1)))
        // Boundary: age == window is NOT fresh, matching the speed path.
        assertFalse(open(drop - window))
        // A ride that never saw traffic cannot confirm anything.
        assertFalse(open(null))
    }
}
