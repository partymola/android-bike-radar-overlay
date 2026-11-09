// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkAwayDeciderTest {

    private val config = WalkAwayDecider.Config(
        enabled = true,
        thresholdMs = 30_000L,
    )

    private fun input(
        nowMs: Long = 10 * 60_000L,
        config: WalkAwayDecider.Config = this.config,
        radarConnected: Boolean = false,
        radarOffSinceMs: Long? = 0L,
        dashcamLastAdvertMs: Long = nowMs - 5_000L,
        dashcamSwitchedOff: Boolean = false,
        armed: Boolean = true,
        sessionTotalRadarConnectedMs: Long = 5 * 60_000L,
        lastFireMs: Long? = null,
        dismissedForEpisode: Boolean = false,
    ) = WalkAwayDecider.Input(
        nowMs = nowMs,
        config = config,
        radarConnected = radarConnected,
        radarOffSinceMs = radarOffSinceMs,
        dashcamLastAdvertMs = dashcamLastAdvertMs,
        dashcamSwitchedOff = dashcamSwitchedOff,
        armed = armed,
        sessionTotalRadarConnectedMs = sessionTotalRadarConnectedMs,
        lastFireMs = lastFireMs,
        dismissedForEpisode = dismissedForEpisode,
    )

    // ── happy path ───────────────────────────────────────────────────────────

    @Test fun `fires when all gates pass`() {
        assertEquals(WalkAwayDecider.Action.FIRE, WalkAwayDecider.decide(input()))
    }

    // ── feature toggle ───────────────────────────────────────────────────────

    @Test fun `disabled config is a no-op`() {
        val disabled = input(config = config.copy(enabled = false))
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(disabled))
    }

    // ── threshold gate ───────────────────────────────────────────────────────

    @Test fun `does not fire before threshold elapsed`() {
        val tooSoon = input(radarOffSinceMs = 10 * 60_000L - 10_000L)
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(tooSoon))
    }

    @Test fun `fires exactly at threshold`() {
        val atThreshold = input(radarOffSinceMs = 10 * 60_000L - 30_000L)
        assertEquals(WalkAwayDecider.Action.FIRE, WalkAwayDecider.decide(atThreshold))
    }

    // ── radar state gates ────────────────────────────────────────────────────

    @Test fun `does not fire while radar is connected`() {
        val connected = input(radarConnected = true)
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(connected))
    }

    @Test fun `does not fire when radar has never been off`() {
        val neverOff = input(radarOffSinceMs = null, radarConnected = false)
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(neverOff))
    }

    // ── cold-start grace ─────────────────────────────────────────────────────

    @Test fun `does not fire before cold-start grace is satisfied`() {
        val coldStart = input(sessionTotalRadarConnectedMs = 1_000L)
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(coldStart))
    }

    // ── dashcam gates ────────────────────────────────────────────────────────

    @Test fun `does not fire when armed=false (BLANK state — rider has packed up)`() {
        // The state-machine BLANK transition (armed=false during an
        // off-episode) means the rider has clearly powered things
        // down — alarm permanently disarmed for this off-episode
        // regardless of whether the dashcam comes back. See the
        // WalkAwayDecider class KDoc for the full state-machine
        // semantics.
        val packedUp = input(armed = false)
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(packedUp))
    }

    @Test fun `does not fire if last dashcam advert is stale`() {
        val now = 10 * 60_000L
        val staleAdvert = input(nowMs = now, dashcamLastAdvertMs = now - 25_000L)
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(staleAdvert))
    }

    @Test fun `a switched-off dashcam does not fire however recent its last read`() {
        // Every other gate green, the read 5 s old.
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(input(dashcamSwitchedOff = true)))
    }

    // ── rate limit ───────────────────────────────────────────────────────────

    @Test fun `does not refire inside rate limit window`() {
        val now = 10 * 60_000L
        val recentFire = input(nowMs = now, lastFireMs = now - 60_000L)
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(recentFire))
    }

    @Test fun `can refire after rate limit window`() {
        val now = 20 * 60_000L
        val oldFire = input(
            nowMs = now,
            radarOffSinceMs = now - 60_000L,
            lastFireMs = now - 6 * 60_000L, // 6 min ago, past 5-min rate limit
        )
        assertEquals(WalkAwayDecider.Action.FIRE, WalkAwayDecider.decide(oldFire))
    }

    // ── dismissal ────────────────────────────────────────────────────────────

    @Test fun `does not fire while dismissed for episode`() {
        val dismissed = input(dismissedForEpisode = true)
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(dismissed))
    }

    // ── auto-dismiss ─────────────────────────────────────────────────────────

    @Test fun `auto-dismisses stale fire after window`() {
        val now = 20 * 60_000L
        val stale = input(nowMs = now, lastFireMs = now - 11 * 60_000L)
        assertEquals(WalkAwayDecider.Action.AUTO_DISMISS_TIME_LIMIT, WalkAwayDecider.decide(stale))
    }

    @Test fun `auto-dismiss takes precedence over rate limit`() {
        // A fire 2 min ago is inside the 5 min rate limit and past a 1 min
        // time limit: the time limit must win.
        val now = 20 * 60_000L
        val both = input(nowMs = now, config = config.copy(autoDismissAfterFireMs = 60_000L), lastFireMs = now - 2 * 60_000L)
        assertEquals(WalkAwayDecider.Action.AUTO_DISMISS_TIME_LIMIT, WalkAwayDecider.decide(both))
    }

    @Test fun `switching the dashcam off stops a fired alarm however recent its last read`() {
        val now = 11 * 60_000L
        val fired = input(nowMs = now, lastFireMs = now - 6 * 60_000L, dashcamLastAdvertMs = now - 1_000L, dashcamSwitchedOff = true)
        assertEquals(WalkAwayDecider.Action.AUTO_DISMISS_SWITCHED_OFF, WalkAwayDecider.decide(fired))
    }

    @Test fun `switching the dashcam off inside the rate limit still stops it`() {
        val now = 11 * 60_000L
        val justFired = input(nowMs = now, lastFireMs = now - 2_000L, dashcamLastAdvertMs = now - 3_000L, dashcamSwitchedOff = true)
        assertEquals(WalkAwayDecider.Action.AUTO_DISMISS_SWITCHED_OFF, WalkAwayDecider.decide(justFired))
    }

    @Test fun `a switched-off dashcam with no alarm sounding is nothing to stop`() {
        val now = 11 * 60_000L
        val noFire = input(nowMs = now, lastFireMs = null, dashcamSwitchedOff = true)
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(noFire))
    }

    @Test fun `a switched-off dashcam past the time limit is named as switched off`() {
        // Both reasons hold; the rider's own act is the one recorded.
        val now = 20 * 60_000L
        val both = input(nowMs = now, lastFireMs = now - 11 * 60_000L, dashcamLastAdvertMs = now - 60_000L, dashcamSwitchedOff = true)
        assertEquals(WalkAwayDecider.Action.AUTO_DISMISS_SWITCHED_OFF, WalkAwayDecider.decide(both))
    }

    @Test fun `auto-dismisses when dashcam goes silent after a fire`() {
        // A fired alarm whose dashcam has gone unheard for over 20 s stops
        // at once.
        val now = 11 * 60_000L
        val firedThenSilenced = input(
            nowMs = now,
            lastFireMs = now - 60_000L,
            dashcamLastAdvertMs = now - 21_000L,
        )
        assertEquals(WalkAwayDecider.Action.AUTO_DISMISS_NOT_HEARD, WalkAwayDecider.decide(firedThenSilenced))
    }

    @Test fun `dashcam-silent dismiss is gated on a prior fire`() {
        // If the dashcam is silent but we never fired, there's no
        // notification to cancel and the regular gates apply (here:
        // dashcam not fresh -> NONE, not AUTO_DISMISS_NOT_HEARD).
        val now = 11 * 60_000L
        val silentNoFire = input(
            nowMs = now,
            lastFireMs = null,
            dashcamLastAdvertMs = now - 21_000L,
        )
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(silentNoFire))
    }

    // ── State-machine regression-proof tests ──────────────────────────
    //
    // Spurious-fire scenario: rider finished a ride (radar off),
    // turned camera off shortly after (BLANK), app stayed running.
    // Several minutes later the rider turned the camera back on to
    // start setting up the next ride — without any state-machine
    // gate, the alarm fires the moment the camera advertises again,
    // which is wrong. These tests pin that BLANK only re-arms via
    // radar reconnect, never via a returning dashcam advert.

    @Test fun `camera-on after BLANK does not re-fire (state-machine pinning)`() {
        // Replays the spurious-fire scenario in decider terms:
        //   - radar disconnected 7 min ago (well past threshold)
        //   - dashcam adverted briefly post-disconnect, then went stale
        //   - service-side disarm logic flipped armed=false (BLANK)
        //   - dashcam now adverts again (rider just turned it on)
        // Service holds armed=false because BLANK only re-arms via
        // radar reconnect. Decider must stay silent.
        val now = 30 * 60_000L
        val radarOffSeven = now - 7 * 60_000L
        val justGotFreshAdvert = input(
            nowMs = now,
            radarOffSinceMs = radarOffSeven,
            dashcamLastAdvertMs = now - 1_000L, // fresh
            armed = false, // BLANK - service disarmed
        )
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(justGotFreshAdvert))
    }

    @Test fun `armed=false blocks fire even when every other gate would pass`() {
        // Negative control: every gate green, only the master state-
        // machine bit is BLANK. Pin this so the armed check stays a
        // hard gate even if the gate order is refactored.
        val allGreenButBlank = input(
            armed = false,
            // every other gate explicitly green:
            radarConnected = false,
            radarOffSinceMs = 0L,
            sessionTotalRadarConnectedMs = 5 * 60_000L,
            dashcamLastAdvertMs = 10 * 60_000L - 5_000L,
            lastFireMs = null,
            dismissedForEpisode = false,
        )
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(allGreenButBlank))
    }

    @Test fun `armed=true still respects all the other gates`() {
        // Negative control of the inverse: armed alone is not enough
        // — the rest of the gates (radar off, threshold, cold-start,
        // dashcam fresh, no recent fire, not dismissed) still apply.
        // Pin this in case someone simplifies the decider to
        // `if (armed) FIRE`.
        val armedButRecentFire = input(
            armed = true,
            lastFireMs = 10 * 60_000L - 60_000L, // 1 min ago, inside rate-limit window
        )
        assertEquals(WalkAwayDecider.Action.NONE, WalkAwayDecider.decide(armedButRecentFire))
    }
}
