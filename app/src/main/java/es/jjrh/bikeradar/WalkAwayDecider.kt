// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Pure-JVM decision engine for the "rider walked away, dashcam still
 * on the bike" alarm. Given the current state + config, returns the
 * action the service should take right now.
 *
 * Scenario being caught: the rider finishes a ride, turns off the
 * radar (or unclips it from the seatpost and takes it with them), and
 * walks off leaving the dashcam still mounted and recording. The
 * rider has the phone, so a local notification with sound + vibration
 * is the correct channel.
 *
 * **State-machine framing**:
 *
 *   IDLE        -- radar connected, riding. No leave-behind possible.
 *   ARMED       -- radar just disconnected, dashcam still seen as alive.
 *                  This is the only state in which FIRE can be returned.
 *   BLANK       -- radar still off but dashcam has been silent long
 *                  enough that the rider is judged to have intentionally
 *                  packed up (or the kit died). No alarm possible until
 *                  the radar reconnects to start a new ride; subsequent
 *                  off-events from this state will only re-arm via the
 *                  next IDLE -> ARMED transition.
 *
 * Transitions:
 *   IDLE  -> ARMED : on radar GATT disconnect (caller stamps).
 *   ARMED -> BLANK : caller observed dashcam stale for [Input.armed]'s
 *                    full disarm window with radar still off.
 *   ARMED -> IDLE  : radar reconnects (next ride begins).
 *   BLANK -> IDLE  : radar reconnects (caller resets `armed` to true on
 *                    the next disconnect, NOT on advert returns within
 *                    the same off-episode).
 *
 * The state machine is OWNED BY THE CALLER (`BikeRadarService`); this
 * decider just receives `armed` as a single boolean each tick and uses
 * it as the master gate. That keeps the state-management logic
 * (timestamp tracking, advert observation) co-located with the live
 * BLE state in the service, while the FIRE/AUTO_DISMISS decision rules
 * stay JVM-pure for testability.
 *
 * The decider is stateless; the caller owns all mutable fields and
 * passes them in via [Input]. That matches the project's existing
 * testable-decider pattern (see `DashcamStatusDeriver`).
 */
object WalkAwayDecider {

    enum class Action {
        /** Nothing to do this tick. */
        NONE,

        /** Post the walk-away notification. The caller must record the
         *  fire timestamp on its own state so the rate limit and auto-
         *  dismiss gates work on the next tick. */
        FIRE,

        /** A previously-fired notification has been live for longer
         *  than [Config.autoDismissAfterFireMs]; cancel it so a stale
         *  alert doesn't sit on the rider's phone forever. */
        AUTO_DISMISS,
    }

    data class Config(
        /** Feature toggle. When false, the decider is a no-op. */
        val enabled: Boolean,
        /** Continuous radar-off duration required before firing. */
        val thresholdMs: Long,
        /** Minimum session-total radar connected time before the
         *  decider is allowed to fire. Prevents alarms during the
         *  first few seconds after service start, where the radar is
         *  still going through the unlock handshake. */
        val coldStartRadarMs: Long = 60_000L,
        /** No new fire within this window after a previous fire. */
        val rateLimitMs: Long = 300_000L,
        /** Maximum time since the last dashcam advert for "dashcam
         *  is still alive" to hold. */
        val dashcamFreshMs: Long = 20_000L,
        /** If a fired notification hasn't been reset by a radar
         *  reconnect or a user dismissal within this window, cancel
         *  it anyway. Prevents a stale alert from sitting forever. */
        val autoDismissAfterFireMs: Long = 600_000L,
    )

    data class Input(
        val nowMs: Long,
        val config: Config,
        /** True when the radar BLE GATT is currently in the CONNECTED
         *  state. */
        val radarConnected: Boolean,
        /** Monotonic timestamp of the last GATT disconnect. Null if
         *  the radar is currently connected or has never been off
         *  this session. */
        val radarOffSinceMs: Long?,
        /** Monotonic timestamp of the last dashcam BLE advert. Used by
         *  the post-fire AUTO_DISMISS path (fire was issued, then the
         *  dashcam went silent → reason for the alert is gone, dismiss
         *  immediately rather than waiting for autoDismissAfterFireMs). */
        val dashcamLastAdvertMs: Long,
        /** Master leave-behind state-machine bit, owned by the caller.
         *
         *  Semantics:
         *
         *    IDLE   (armed=false): radar connected, riding. No leave-
         *           behind possible.
         *    ARMED  (armed=true): radar just disconnected with the
         *           dashcam still alive on the bike. FIRE is gated on
         *           this state.
         *    BLANK  (armed=false): radar still off but the rider has
         *           clearly packed up — dashcam has been silent for
         *           ≥dashcamFreshMs since radar disconnect. The rider
         *           has done something deliberate (turned the camera
         *           off, the kit died, etc.) so this off-episode is
         *           NOT a leave-behind risk. Stays BLANK until the
         *           radar reconnects (next ride) and then disconnects
         *           again.
         *
         *  Caller transitions:
         *    IDLE  -> ARMED : on radar disconnect (`markRadarDisconnected`).
         *    ARMED -> BLANK : when dashcam stale-since-or-before-radar-off
         *                     exceeds freshness window during the tick
         *                     (see `BikeRadarService.tickWalkAwayState`).
         *    ARMED -> IDLE  : on radar reconnect (`markRadarConnected`).
         *    BLANK -> IDLE  : on radar reconnect.
         *
         *  Critically: `armed` does NOT re-flip to true mid-off-
         *  episode even if the dashcam comes back. A rider turning
         *  the camera back on between rides should not re-arm the
         *  alarm. Re-arming requires the next radar power-on/off
         *  cycle. */
        val armed: Boolean,
        /** Running total of how long the radar has been CONNECTED this
         *  session. */
        val sessionTotalRadarConnectedMs: Long,
        /** Monotonic timestamp of the last FIRE event, or null if no
         *  fire has happened this session. */
        val lastFireMs: Long?,
        /** True when the user dismissed the last fire and the decider
         *  must not re-fire until the radar reconnects (which clears
         *  this flag on the caller's side). */
        val dismissedForEpisode: Boolean,
    )

    fun decide(i: Input): Action {
        val c = i.config
        if (!c.enabled) return Action.NONE

        // If the rider has already been alerted and now turns the
        // dashcam off, the alert's reason is gone - cancel the
        // notification right away rather than leaving it up until
        // autoDismissAfterFireMs. Checked before the rate-limit and
        // auto-dismiss-after-fire gates so it wins over both.
        if (i.lastFireMs != null && i.nowMs - i.dashcamLastAdvertMs > c.dashcamFreshMs) {
            return Action.AUTO_DISMISS
        }

        // Auto-dismiss a stale, still-live notification first. Runs
        // ahead of the rate limit so a fire that's been ignored for
        // autoDismissAfterFireMs is cleared and not just silenced.
        if (i.lastFireMs != null && i.nowMs - i.lastFireMs >= c.autoDismissAfterFireMs) {
            return Action.AUTO_DISMISS
        }

        // Rate limit: no second fire within the window.
        if (i.lastFireMs != null && i.nowMs - i.lastFireMs < c.rateLimitMs) {
            return Action.NONE
        }

        // User dismissed this episode via the notification action;
        // don't refire until the caller clears the flag on radar
        // reconnect.
        if (i.dismissedForEpisode) return Action.NONE

        // ── State-machine master gate ─────────────────────────────────
        // FIRE is only legal in the ARMED state. The state-machine
        // logic (IDLE -> ARMED -> BLANK -> IDLE) lives in
        // BikeRadarService; this decider treats `armed` as the master
        // gate. The BLANK state (armed=false mid-off-episode) blocks
        // a spurious alarm when the rider turns the camera back on
        // between rides without first powering the radar on. Re-arm
        // is via radar reconnect only.
        if (!i.armed) return Action.NONE

        // Radar must be currently off, and off for long enough.
        if (i.radarConnected) return Action.NONE
        val off = i.radarOffSinceMs ?: return Action.NONE
        if (i.nowMs - off < c.thresholdMs) return Action.NONE

        // Cold-start grace: require a minimum session-connected time
        // before we'll ever fire. Prevents alarms during the initial
        // unlock handshake or if the app is started with the bike
        // already locked up next to a dashcam that's still on.
        if (i.sessionTotalRadarConnectedMs < c.coldStartRadarMs) return Action.NONE

        // Dashcam must currently be alive. (The "has adverted since
        // radar off" guard from the previous design is now subsumed
        // by the `armed` state gate above: if `armed` is true, by
        // construction the dashcam was alive at radar-off time and
        // has been continuously sighted since — caller-side disarm
        // logic flips `armed` to false the moment the dashcam goes
        // stale during an off-episode.)
        if (i.nowMs - i.dashcamLastAdvertMs > c.dashcamFreshMs) return Action.NONE

        return Action.FIRE
    }
}
