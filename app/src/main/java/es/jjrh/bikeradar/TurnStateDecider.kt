// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlin.math.abs

/**
 * Pure-JVM turn state machine for turn-aware alerting.
 *
 * Fed yaw-rate samples (rotation about the gravity axis, rad/s, from
 * [TurnSensorController]), it answers one question: is the rider currently
 * carving a substantial corner, or has just finished one? [AlertDecider]
 * uses the answer to defer the all-clear, because a corner makes the radar
 * drop every followed car (the rear cone sweeps off them) and reacquire it
 * seconds later - an empty stream mid-turn is a blackout, not an empty
 * road, and sounding "road clear" then would be a false all-clear. Beeps
 * are never gated on this state: a reacquired follower beeps again.
 *
 * States:
 *  - **IDLE** - no rotation episode in progress.
 *  - **TURNING** - a rotation episode whose cumulative signed heading
 *    change has reached [turnAngleDeg]. Episodes start when |yaw rate|
 *    crosses [rateFloorRadS] and integrate signed rate over time, so
 *    steering wobble (measured at ~2 degrees amplitude on ride captures,
 *    mostly below the rate floor and self-cancelling) never accumulates
 *    to a turn.
 *  - **HOLD** - the qualified episode ended (rate below the floor for
 *    [quietEndMs]); the state stays HOLD for [holdMs] more. This is the
 *    window in which [AlertDecider] may anchor its adaptive clear-
 *    deferral tail (sized by follower distance over rider speed), not a
 *    fixed suppression window - an anchored tail runs to completion
 *    even if HOLD ends first.
 *
 * A rotation episode that ends below [turnAngleDeg] (lane change, gentle
 * bend, steering correction) returns straight to IDLE with no hold.
 * Opposite-direction rotation within one episode cancels (signed sum), so
 * an S-wiggle does not qualify.
 *
 * Not thread-safe; feed and query from a single thread (the sensor
 * listener's). [TurnSensorController] publishes the result across threads.
 */
class TurnStateDecider(
    turnAngleDeg: Float = TURN_ANGLE_DEG,
    private val rateFloorRadS: Float = RATE_FLOOR_RAD_S,
    private val quietEndMs: Long = QUIET_END_MS,
    private val holdMs: Long = HOLD_MS,
) {
    enum class State { IDLE, TURNING, HOLD }

    private val turnAngleRad = Math.toRadians(turnAngleDeg.toDouble()).toFloat()

    private var inEpisode = false
    private var qualified = false
    private var cumRad = 0f
    private var lastSampleMs = 0L
    private var lastAboveFloorMs = 0L
    private var holdUntilMs = Long.MIN_VALUE

    /** True while a rotation episode is in progress, qualifying or not.
     *  Distinct from [State.TURNING], which additionally requires
     *  [turnAngleDeg] to have accumulated: an episode is live from the
     *  first sample above the rate floor, so this covers a corner's entry
     *  phase and an unqualified episode during the post-turn HOLD window,
     *  both of which read as not-TURNING. (A HOLD-window episode that does
     *  reach the angle reports TURNING from that sample, like any other.) */
    val episodeActive: Boolean get() = inEpisode

    /** Signed integral of yaw rate over the current rotation episode, in
     *  degrees; zero between episodes. The sign is whatever the caller's
     *  yaw-rate sign means - for the shipped feed, see
     *  [TurnSensorController.yawRateAboutGravity].
     *
     *  This is integrated steering, NOT heading change, and the two are not
     *  interchangeable: a roughly 90-degree junction measured about 175 and
     *  about -196 degrees on its two legs (see
     *  [TurnSensorController.yawRateAboutGravity]). Use it to tell a corner
     *  from a wobble, never to report how far the rider turned.
     *
     *  A stalled sensor stream understates it: the integration step is
     *  clamped to [MAX_SAMPLE_GAP_MS], so an episode spanning a stall
     *  yields a floor on the integral rather than the integral. */
    val cumulativeDeg: Float get() = Math.toDegrees(cumRad.toDouble()).toFloat()

    /** Signed integrated steering of the last COMPLETED rotation episode, in
     *  degrees; sign convention as for [cumulativeDeg].
     *  [cumulativeDeg] is cleared when an episode closes, so
     *  without this the total angle of a corner - the only thing that says
     *  which way the rider went - is lost at the moment it becomes
     *  knowable.
     *
     *  Every completed episode overwrites it, qualifying or not, so a
     *  sub-threshold wobble during the post-turn HOLD window will replace
     *  a corner's total with a few degrees. Read it on the transition, not
     *  later. */
    var lastEpisodeDeg: Float = 0f
        private set

    /** Feed one yaw-rate sample. [nowMs] must be monotonic (elapsedRealtime). */
    fun onYawSample(yawRateRadS: Float, nowMs: Long) {
        val above = abs(yawRateRadS) >= rateFloorRadS
        if (!inEpisode) {
            if (above) {
                inEpisode = true
                qualified = false
                cumRad = 0f
                lastAboveFloorMs = nowMs
            }
            lastSampleMs = nowMs
            return
        }
        // Integrate signed rate. dt is clamped so a sensor stall cannot
        // manufacture a huge angle from one late sample.
        val dtMs = (nowMs - lastSampleMs).coerceIn(0L, MAX_SAMPLE_GAP_MS)
        cumRad += yawRateRadS * dtMs / 1000f
        lastSampleMs = nowMs
        if (above) lastAboveFloorMs = nowMs
        if (abs(cumRad) >= turnAngleRad) qualified = true
        if (!above && nowMs - lastAboveFloorMs >= quietEndMs) {
            // Episode over: rotation has been below the floor long enough.
            if (qualified) holdUntilMs = nowMs + holdMs
            lastEpisodeDeg = cumulativeDeg
            inEpisode = false
            qualified = false
            cumRad = 0f
        }
    }

    fun stateAt(nowMs: Long): State = when {
        inEpisode && qualified -> State.TURNING
        nowMs < holdUntilMs -> State.HOLD
        else -> State.IDLE
    }

    /** True while TURNING or within the post-turn HOLD window. Convenience
     *  over [stateAt]; [AlertDecider] consumes the full state (TURNING
     *  defers the clear, HOLD anchors the adaptive tail). */
    fun holdActive(nowMs: Long): Boolean = stateAt(nowMs) != State.IDLE

    fun reset() {
        inEpisode = false
        qualified = false
        cumRad = 0f
        lastEpisodeDeg = 0f
        lastSampleMs = 0L
        lastAboveFloorMs = 0L
        holdUntilMs = Long.MIN_VALUE
    }

    companion object {
        /** Integrated steering (degrees, see [cumulativeDeg]) for a rotation
         *  episode to count as a turn. 60 catches 90-degree corners and
         *  strong roundabout exits - the manoeuvres observed to drop every
         *  followed track - while ignoring lane changes and gentle bends,
         *  which do not. Steering wobble integrates to a few degrees at
         *  most before the quiet-end resets the episode.
         *
         *  Do not read 60 as "a 60-degree corner". The quantity is
         *  integrated steering, so a measured 90-degree junction ran to
         *  about 175 degrees: the threshold trips partway INTO such a
         *  corner, which is what the alert hold wants. */
        const val TURN_ANGLE_DEG = 60f

        /** Yaw rate (rad/s, ~8.6 deg/s) that opens and sustains a rotation
         *  episode. Ride-capture steering wobble oscillates well below
         *  this; a real corner at urban speed sweeps 20-30 deg/s. */
        const val RATE_FLOOR_RAD_S = 0.15f

        /** Continuous below-floor time (ms) that ends a rotation episode.
         *  Long enough to bridge the mid-corner moment where the rider
         *  straightens between two phases of one junction; short enough
         *  that the post-turn hold starts promptly. */
        const val QUIET_END_MS = 700L

        /** Post-turn HOLD duration (ms) - the window in which
         *  [AlertDecider] may anchor its adaptive clear-deferral tail,
         *  not itself a suppression period. Matches the tail's upper
         *  clamp ([AlertDecider.TURN_TAIL_MAX_MS]): once the longest
         *  possible tail could have been anchored, keeping the state in
         *  HOLD buys nothing. Ride captures put turn-shaped
         *  reacquisitions 2-10 s after the turn, inside this window. */
        const val HOLD_MS = 10_000L

        /** Clamp on the integration step so a stalled sensor stream cannot
         *  turn one late sample into a large phantom angle. */
        private const val MAX_SAMPLE_GAP_MS = 250L
    }
}
