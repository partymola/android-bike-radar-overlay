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
        /** Monotonic timestamp of the last dashcam BLE advert. */
        val dashcamLastAdvertMs: Long,
        /** True iff at least one dashcam advert has been observed
         *  after the most recent radar-off event. Distinguishes "dash-
         *  cam still on bike while rider walks away" (this stays
         *  true as long as the rider is within advert range of the
         *  bike) from "rider took everything inside together" (false,
         *  dashcam went silent as it went out of phone range). */
        val dashcamHasAdvertedSinceRadarOff: Boolean,
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

        // Radar must be currently off, and off for long enough.
        if (i.radarConnected) return Action.NONE
        val off = i.radarOffSinceMs ?: return Action.NONE
        if (i.nowMs - off < c.thresholdMs) return Action.NONE

        // Cold-start grace: require a minimum session-connected time
        // before we'll ever fire. Prevents alarms during the initial
        // unlock handshake or if the app is started with the bike
        // already locked up next to a dashcam that's still on.
        if (i.sessionTotalRadarConnectedMs < c.coldStartRadarMs) return Action.NONE

        // Dashcam must currently be alive AND must have adverted at
        // least once since radar went off. The latter rules out "both
        // devices went out of phone range together" (phone-left-
        // behind scenario), which is not our target case.
        if (!i.dashcamHasAdvertedSinceRadarOff) return Action.NONE
        if (i.nowMs - i.dashcamLastAdvertMs > c.dashcamFreshMs) return Action.NONE

        return Action.FIRE
    }
}
