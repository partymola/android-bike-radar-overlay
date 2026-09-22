// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
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
 * road, and sounding "road clear" then would be a false all-clear.
 * [BornCloseGate] also reads it, discounting closing evidence while
 * TURNING, so a TURNING that outlives the corner costs beeps as well as
 * all-clears.
 *
 * States:
 *  - **IDLE** - not turning, no post-turn hold.
 *  - **TURNING** - the net signed rotation over the last [WINDOW_MS] has
 *    reached [TURN_ENTER_DEG]. It ends when the rotation episode goes quiet
 *    (rate below [rateFloorRadS] for [quietEndMs]), or when the window net
 *    has fallen below [TURN_EXIT_DEG] AND the newest [SUSTAIN_MS] has netted
 *    under [SUSTAIN_DEG] continuously for [CONFIRM_MS], or when the window
 *    net has not reached the entry angle for [STALL_MS].
 *  - **HOLD** - [holdMs] after TURNING ends. This is the window in which
 *    [AlertDecider] may anchor its adaptive clear-deferral tail (sized by
 *    follower distance over rider speed), not a fixed suppression window -
 *    an anchored tail runs to completion even if HOLD ends first.
 *
 * The phone rides on the handlebar, so the gyro sees steering as well as
 * heading. Steering oscillates about straight-ahead and its integral stays
 * bounded, while a corner keeps adding heading, so a few seconds of net
 * rotation separates the two. Wobble alone can keep the rate above the floor
 * indefinitely, so the episode cannot be what ends a turn
 * (`wobbleAfterACornerDoesNotHoldTheTurnOpen`).
 *
 * The contract, each row pinned by the test named:
 *
 *  - HOLDS through a reversal's second leg that never dwells under the rate
 *    floor for [quietEndMs], with wobble up to the measured 90th percentile
 *    (0.4 rad/s, 0.8 s half-swing): `aReversalAtCorneringRateStaysTurning`,
 *    `aSlowReversalStaysTurning`, `aReversalWithWobbleStaysTurning`,
 *    `aSlowWobblingReversalStaysTurning`,
 *    `aMomentaryLullInAWobblingLegDoesNotEndTheTurn`. A slow leg whose
 *    wobble does drag it under the floor for that long is a residual, below.
 *  - RELEASES after wobble whose half-swing stays under the entry angle: a
 *    few seconds for measured wobble (`wobbleAfterACornerDoesNotHoldTheTurnOpen`),
 *    at most [STALL_MS] after the window last reached the entry angle for any
 *    (`largeWobbleAfterACornerCannotHoldTheTurn`).
 *  - RELEASES a bend below the rate floor even while it continues: through
 *    the quiet exit when the bend alone is all there is
 *    (`aBendBelowTheFloorEndsTheTurnAtTheQuietExit`), and, when wobble
 *    keeps the episode alive, [STALL_MS] after the window last reached the
 *    entry angle (`aGentleBendAfterACornerHoldsTheTurnUntilTheStallCap`).
 *    Wobble can also carry a bend near the floor over the entry angle, so
 *    the cut-off between holding and releasing sits around 8 deg/s and moves
 *    with the wobble.
 *
 * Residuals, stated so they are not mistaken for guarantees. A leg that
 * dwells under the rate floor for [quietEndMs], such as a reversal passing
 * slowly through zero or a slow leg whose wobble drags it under the floor,
 * ends the turn through the quiet exit while the bike may still be turning.
 * A weave slower or larger than the measured wobble (half-swing near a
 * second or more, or amplitude past 0.5 rad/s) can end a turn inside a leg
 * through the window exit, or hold TURNING while it lasts, each of its
 * half-cycles then being a turn by the entry rule. So
 * HOLD means the rotation has gone quiet, stopped being sustained, or stopped
 * reaching the entry angle. That is usually the end of the corner, not a
 * guarantee of it.
 *
 * The window restarts whenever TURNING ends, so the corner just finished
 * cannot re-qualify from the same rotation. It is NOT cleared when an episode
 * that never turned goes quiet, so two bends in one direction within the
 * window add up (`twoBendsOneSecondApartAreOneTurn`). Opposite-direction
 * rotation cancels in the signed sum, so an S-wiggle whose legs each stay
 * under the entry angle does not qualify.
 *
 * Not thread-safe; feed and query from a single thread (the sensor
 * listener's). [TurnSensorController] publishes the result across threads.
 */
class TurnStateDecider(
    private val rateFloorRadS: Float = RATE_FLOOR_RAD_S,
    private val quietEndMs: Long = QUIET_END_MS,
    private val holdMs: Long = HOLD_MS,
) {
    enum class State { IDLE, TURNING, HOLD }

    private var inEpisode = false
    private var cumRad = 0f
    private var lastSampleMs = 0L
    private var lastAboveFloorMs = 0L
    private var holdUntilMs = Long.MIN_VALUE

    /** (sample ms, integrated rad) inside the last [WINDOW_MS]; [windowRad] is their sum. */
    private val window = ArrayDeque<Pair<Long, Float>>()
    private var windowRad = 0f
    private var turning = false
    private var turnRad = 0f

    /** Since when the newest [SUSTAIN_MS] has netted under [SUSTAIN_DEG] on
     *  every sample; [Long.MIN_VALUE] while it has not. The span is read out
     *  of the window, so for [SUSTAIN_MS] after the window restarts it sums
     *  only the samples since then. */
    private var calmSinceMs = Long.MIN_VALUE

    /** When the window net last reached the entry angle; see [STALL_MS]. */
    private var lastAtEntryMs = 0L

    /** True while a rotation episode is in progress, turning or not. An
     *  episode is live from the first sample above the rate floor, so this
     *  covers a corner's entry phase and rotation during the post-turn HOLD
     *  window, both of which read as not-TURNING. It gates the capture
     *  log's yaw trace. */
    val episodeActive: Boolean get() = inEpisode

    /** Signed integral of yaw rate over the current rotation episode, in
     *  degrees; zero between episodes. The sign is whatever the caller's
     *  yaw-rate sign means - for the shipped feed, see
     *  [TurnSensorController.yawRateAboutGravity].
     *
     *  This is integrated steering, NOT heading change, and the two are not
     *  interchangeable: a roughly 90-degree junction measured about 175 and
     *  about -196 degrees on its two legs (see
     *  [TurnSensorController.yawRateAboutGravity]). It feeds the capture log's
     *  yaw trace only; the turn decision reads the window, not this.
     *
     *  A stalled sensor stream understates it: the integration step is
     *  clamped to [MAX_SAMPLE_GAP_MS], so an episode spanning a stall
     *  yields a floor on the integral rather than the integral. */
    val cumulativeDeg: Float get() = Math.toDegrees(cumRad.toDouble()).toFloat()

    /** Net signed rotation in the window, in degrees: the quantity TURNING is
     *  entered on. */
    val windowDeg: Float get() = Math.toDegrees(windowRad.toDouble()).toFloat()

    /** Signed rotation of the last COMPLETED turn, in degrees, from the start
     *  of the window that qualified it to the sample that ended it; sign
     *  convention as for [cumulativeDeg]. Only a turn writes it: rotation
     *  that never reached TURNING leaves it alone. It includes rotation from
     *  before the turn that was still in the window at entry, and for a
     *  two-way manoeuvre it is the net, which can be small. Integrated
     *  steering, so read the sign, not the size. */
    var lastTurnDeg: Float = 0f
        private set

    /** Feed one yaw-rate sample. [nowMs] must be monotonic (elapsedRealtime). */
    fun onYawSample(yawRateRadS: Float, nowMs: Long) {
        val above = abs(yawRateRadS) >= rateFloorRadS
        if (!inEpisode) {
            if (above) {
                inEpisode = true
                cumRad = 0f
                lastAboveFloorMs = nowMs
            }
            lastSampleMs = nowMs
            return
        }
        // Integrate signed rate. dt is clamped so a sensor stall cannot
        // manufacture a huge angle from one late sample.
        val dtMs = (nowMs - lastSampleMs).coerceIn(0L, MAX_SAMPLE_GAP_MS)
        val stepRad = yawRateRadS * dtMs / 1000f
        cumRad += stepRad
        lastSampleMs = nowMs
        if (above) lastAboveFloorMs = nowMs
        window.addLast(nowMs to stepRad)
        windowRad += stepRad
        while (window.first().first <= nowMs - WINDOW_MS) windowRad -= window.removeFirst().second
        if (turning) {
            turnRad += stepRad
        } else if (abs(windowRad) >= ENTER_RAD) {
            turning = true
            turnRad = windowRad
        }
        if (abs(windowRad) >= ENTER_RAD) lastAtEntryMs = nowMs
        if (abs(recentRad(nowMs)) < SUSTAIN_RAD) {
            if (calmSinceMs == Long.MIN_VALUE) calmSinceMs = nowMs
        } else {
            calmSinceMs = Long.MIN_VALUE
        }
        val calm = calmSinceMs != Long.MIN_VALUE && nowMs - calmSinceMs >= CONFIRM_MS
        val quiet = !above && nowMs - lastAboveFloorMs >= quietEndMs
        val stalled = nowMs - lastAtEntryMs >= STALL_MS
        if (turning && (quiet || stalled || (abs(windowRad) < EXIT_RAD && calm))) {
            turning = false
            holdUntilMs = nowMs + holdMs
            lastTurnDeg = Math.toDegrees(turnRad.toDouble()).toFloat()
            window.clear()
            windowRad = 0f
            calmSinceMs = Long.MIN_VALUE
        }
        if (quiet) {
            inEpisode = false
            cumRad = 0f
            calmSinceMs = Long.MIN_VALUE
        }
    }

    /** Net rotation in the window's newest [SUSTAIN_MS]. */
    private fun recentRad(nowMs: Long): Float {
        var sum = 0f
        for (i in window.indices.reversed()) {
            val (t, step) = window[i]
            if (t <= nowMs - SUSTAIN_MS) break
            sum += step
        }
        return sum
    }

    fun stateAt(nowMs: Long): State = when {
        turning -> State.TURNING
        nowMs < holdUntilMs -> State.HOLD
        else -> State.IDLE
    }

    /** True while TURNING or within the post-turn HOLD window. Convenience
     *  over [stateAt]; [AlertDecider] consumes the full state (TURNING
     *  defers the clear, HOLD anchors the adaptive tail). */
    fun holdActive(nowMs: Long): Boolean = stateAt(nowMs) != State.IDLE

    fun reset() {
        inEpisode = false
        cumRad = 0f
        lastTurnDeg = 0f
        lastSampleMs = 0L
        lastAboveFloorMs = 0L
        holdUntilMs = Long.MIN_VALUE
        window.clear()
        windowRad = 0f
        turning = false
        calmSinceMs = Long.MIN_VALUE
        lastAtEntryMs = 0L
    }

    companion object {
        /** Net rotation (degrees) inside [WINDOW_MS] that starts a turn, chosen
         *  with the other window constants by replaying ride yaw traces against
         *  GPS corners; a 29 deg/s corner qualifies 1.5 s in
         *  (`aCornerQualifiesAtTheEntryAngleOfNetRotation`). At 40 the
         *  40-degree first leg of `sWiggleCancelsAndDoesNotQualify` would
         *  qualify. */
        const val TURN_ENTER_DEG = 42f

        /** Window net below which a turn may end, if the rotation is no longer
         *  sustained. Below the entry angle so the state cannot flap. Pinned
         *  on both sides by the exact exit time in
         *  `wobbleAfterACornerDoesNotHoldTheTurnOpen`. */
        const val TURN_EXIT_DEG = 25f

        /** Length (ms) of the net-rotation window. Long enough to hold a
         *  slow corner (`aSlowCornerQualifiesInsideTheWindow`); short enough
         *  that two 34-degree bends three seconds apart do not add up
         *  (`twoSubThresholdEpisodesDoNotAccumulate`). */
        const val WINDOW_MS = 5_000L

        /** A turn may only end through the window once the newest [SUSTAIN_MS]
         *  has netted under [SUSTAIN_DEG] on every sample for [CONFIRM_MS].
         *
         *  The span has to hold a whole wobble cycle: shortened below the 2 s
         *  cycle of that test's wobble, with the bound scaled to the same
         *  5 deg/s (1.5 s at 7.5 degrees), the wobble's own swing keeps the net
         *  over it, calm is never confirmed, and only the stall cap ends a turn
         *  (`wobbleAfterACornerDoesNotHoldTheTurnOpen`). Measured on straight
         *  road the half-swing is 0.4 s at the median and 0.8 s at the 90th
         *  percentile. It also has to be long enough for a slow second leg to
         *  net over the bound: a 6.9 deg/s leg nets 20.6 over 3 s and 13.75
         *  over 2 s (`aSlowWobblingReversalStaysTurning`, which a 2 s span at
         *  the same 15-degree bound fails, as does
         *  `largeWobbleAfterACornerCannotHoldTheTurn`). The bound is 5 deg/s
         *  over the span, while the slowest rotation that keeps an episode
         *  alive (8.6 deg/s) nets about 26 (`aSlowReversalStaysTurning`).
         *  The confirmation stops a brief dip, a fraction of a second in which
         *  a leg's wobble happens to cancel it, from ending the turn
         *  (`aMomentaryLullInAWobblingLegDoesNotEndTheTurn`). */
        const val SUSTAIN_MS = 3_000L
        const val SUSTAIN_DEG = 15f
        const val CONFIRM_MS = 500L

        /** A turn ends once the window net has not reached [TURN_ENTER_DEG]
         *  for this long. A turn at about 8 deg/s or faster keeps topping it,
         *  and a reversal's second leg reaches it the other way; wobble whose
         *  half-swing stays under the entry angle never does, and near some
         *  periods its swing keeps the sustain span from confirming calm, which
         *  without this cap held TURNING indefinitely
         *  (`largeWobbleAfterACornerCannotHoldTheTurn`). Over the same reversal
         *  cases the cap changed nothing, and replaying the recorded commutes
         *  with and without it gave the same scores. */
        const val STALL_MS = 8_000L

        /** Yaw rate (rad/s, ~8.6 deg/s) that opens and sustains a rotation
         *  episode, which gates the capture log's yaw trace and the quiet-end
         *  exit. Only rotation inside an episode is integrated. */
        const val RATE_FLOOR_RAD_S = 0.15f

        /** Continuous below-floor time (ms) that ends a rotation episode, and
         *  a turn with it. Pinned by
         *  `aBendBelowTheFloorEndsTheTurnAtTheQuietExit`: the turn ends exactly
         *  700 ms after the last sample above the floor. */
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

        private val ENTER_RAD = Math.toRadians(TURN_ENTER_DEG.toDouble()).toFloat()
        private val EXIT_RAD = Math.toRadians(TURN_EXIT_DEG.toDouble()).toFloat()
        private val SUSTAIN_RAD = Math.toRadians(SUSTAIN_DEG.toDouble()).toFloat()
    }
}
