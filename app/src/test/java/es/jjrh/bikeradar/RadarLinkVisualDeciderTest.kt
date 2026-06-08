// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [RadarLinkVisualDecider]. The banner shows once the radar has been down
 * past the threshold AND connected at least once this session AND not paused,
 * and is then bounded + cohort-aware: an eBike rider's banner persists while
 * unlocked (capped by a forgot-to-lock backstop, hidden on explicit lock); a
 * radar-only rider's banner retires after a short cap unless they opt into
 * persistence. (This cohort-awareness deliberately REVERSES the earlier
 * "never gate the visual" design - see the class KDoc for why: an unbounded
 * overlay is an uninstall driver.)
 */
class RadarLinkVisualDeciderTest {

    private val threshold = 10_000L
    private val ebikeMax = 300_000L
    private val radarOnlyMax = 40_000L

    private val live = RadarLinkVisualDecider.LinkVisual.LIVE
    private val plain = RadarLinkVisualDecider.LinkVisual.RECONNECTING_PLAIN
    private val unlocked = RadarLinkVisualDecider.LinkVisual.RECONNECTING_UNLOCKED

    private fun decide(
        everLive: Boolean = true,
        downForMs: Long?,
        paused: Boolean = false,
        hasEBike: Boolean = false,
        explicitParked: Boolean = false,
        persistent: Boolean = false,
    ) = RadarLinkVisualDecider.decide(
        radarEverLive = everLive,
        radarDownForMs = downForMs,
        visualThresholdMs = threshold,
        paused = paused,
        hasEBikeSignal = hasEBike,
        explicitParked = explicitParked,
        ebikeMaxMs = ebikeMax,
        radarOnlyMaxMs = radarOnlyMax,
        radarOnlyPersistent = persistent,
    )

    // ── base gate (applies to both cohorts) ──────────────────────────────────

    @Test fun connectedIsLive() = assertEquals(live, decide(downForMs = null))

    @Test fun belowThresholdStaysLive() = assertEquals(live, decide(downForMs = threshold - 1))

    @Test fun coldStartIsLiveEvenWhenLongDown() = assertEquals(live, decide(everLive = false, downForMs = threshold * 100))

    @Test fun pausedStaysLiveEvenWhenDown() = assertEquals(live, decide(downForMs = threshold + 5_000, paused = true))

    // ── radar-only cohort (no eBike signal) ──────────────────────────────────

    @Test fun radarOnlyShowsPlainAtThreshold() = assertEquals(plain, decide(downForMs = threshold, hasEBike = false))

    @Test fun radarOnlyRetiresAtCap() = assertEquals(live, decide(downForMs = radarOnlyMax, hasEBike = false))

    @Test fun radarOnlyJustUnderCapStillShows() = assertEquals(plain, decide(downForMs = radarOnlyMax - 1, hasEBike = false))

    @Test fun radarOnlyPersistentToggleIgnoresCap() = assertEquals(plain, decide(downForMs = radarOnlyMax * 100, hasEBike = false, persistent = true))

    // ── eBike cohort ─────────────────────────────────────────────────────────

    @Test fun ebikeUnlockedShowsUnlockedMessage() = assertEquals(unlocked, decide(downForMs = threshold, hasEBike = true, explicitParked = false))

    @Test fun ebikeExplicitlyParkedHides() = assertEquals(live, decide(downForMs = threshold + 5_000, hasEBike = true, explicitParked = true))

    @Test
    fun ebikeStaleSnapshotKeepsShowing() {
        // A stale Flow snapshot maps to explicitParked == false in the caller, so
        // a simultaneous Flow+radar dropout does NOT hide the banner (the visual
        // stays on its own failure mode, uncoupled from the audio cue).
        assertEquals(unlocked, decide(downForMs = threshold + 5_000, hasEBike = true, explicitParked = false))
    }

    @Test fun ebikeRetiresAtForgotToLockBackstop() = assertEquals(live, decide(downForMs = ebikeMax, hasEBike = true, explicitParked = false))

    @Test fun ebikeJustUnderBackstopStillShows() = assertEquals(unlocked, decide(downForMs = ebikeMax - 1, hasEBike = true, explicitParked = false))

    @Test
    fun ebikeNotSubjectToRadarOnlyCap() {
        // The 40s radar-only cap must not apply to an eBike rider - their banner
        // persists past it (up to the 5-min backstop) while unlocked.
        assertEquals(unlocked, decide(downForMs = radarOnlyMax + 5_000, hasEBike = true, explicitParked = false))
    }

    @Test
    fun reconnectReturnsToLive() {
        assertEquals(unlocked, decide(downForMs = threshold + 1, hasEBike = true))
        // Radar returns (downForMs back to null) -> LIVE, no latch to leak.
        assertEquals(live, decide(downForMs = null, hasEBike = true))
    }
}
