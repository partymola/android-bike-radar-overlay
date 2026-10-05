// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

/**
 * Snapshot of the rear-radar BLE link and the walk-away state machine that
 * watches it. Held in a single [kotlinx.coroutines.flow.MutableStateFlow] on
 * [BikeRadarService] so multi-field transitions are atomic against readers -
 * before this consolidation, the cluster was seven separate `@Volatile` fields
 * and a reader could observe a half-finished transition (radar marked
 * disconnected, off-instant not yet stamped; or off-instant cleared, armed
 * flag not yet reset).
 *
 * The `walkAwaySnoozeJob` ([java.util.concurrent.atomic.AtomicReference] on
 * the service) is intentionally NOT part of this state: it's a cancellable
 * side effect, not pure state, and `update { }` on a CAS loop may run its
 * body multiple times (would leak a job per retry).
 *
 * All fields default to "radar has never been seen", which matches the
 * service's pre-onCreate state.
 */
data class RadarLinkState(
    /** True while the GATT link to the rear radar is open. */
    val radarGattActive: Boolean = false,
    /** Monotonic (elapsedRealtime) ms the current off-episode began: the first
     *  disconnect after a connection, or the first connect attempt that failed
     *  (`theFirstRadarForgetsAnEarlierLockEvenAfterAFailedAttempt`). Null while
     *  the radar is connected, or before the first of either this session. */
    val radarOffSinceMs: Long? = null,
    /** Monotonic (elapsedRealtime) ms when the current radar connection began.
     *  Null while disconnected. Used to integrate [sessionRadarConnectedMs] on
     *  the next disconnect. */
    val radarConnectStartMs: Long? = null,
    /** Total ms the radar has been GATT-connected this session, integrated
     *  on each connect -> disconnect transition (not per-tick: the idle
     *  tick is 30 s and a connection that ends within that window would
     *  go unnoticed under a per-tick scheme). */
    val sessionRadarConnectedMs: Long = 0L,
    /** Monotonic (elapsedRealtime) ms the radar link was last up: stamped on a
     *  connect, and on a disconnect that ends a connection rather than a failed
     *  attempt. Null if never this session. Answers "was the radar up during
     *  this ride" for the no-radar warning. */
    val lastRadarUpMs: Long? = null,
    /** True after a radar disconnect when the walk-away decider is watching
     *  the dashcam for a leave-behind. Disarmed when the dashcam goes stale
     *  (BLANK) or the radar comes back (IDLE). */
    val walkAwayArmed: Boolean = false,
    /** True once the rider has dismissed the walk-away alarm for the
     *  current off-episode (cleared when the radar reconnects or after the
     *  snooze window elapses). */
    val walkAwayDismissed: Boolean = false,
    /** Monotonic (elapsedRealtime) ms of the most recent walk-away alarm fire,
     *  or null if none has fired this episode. */
    val lastWalkAwayFireMs: Long? = null,
    /** True once the rider has said this off-episode is the end of a ride.
     *
     *  The radar-only equivalent of a Bosch eBike reporting `system_locked ==
     *  true`, and read through the same `explicitParked` path, so it carries
     *  the same effects: the drop cue is vetoed, its latch closed out, and the
     *  dead-radar banner retired. Without it the app has to INFER a ride end
     *  from traffic, which is the guessing the whole gate exists to bound.
     *
     *  Cleared on the next radar connect, or when an eBike riding run begins
     *  after [rideEndedAtMs], so it cannot silence a later ride whose radar
     *  stays off (`aParkedTapDoesNotSilenceTheNextRideWithoutTheRadar`). A run
     *  begins after a stop longer than `RidingSpeedGate.FRESH_MS`, so riding
     *  on sooner than that is still the ride the tap ended
     *  (`aParkedTapDuringTheRunItEndedStillHolds`). */
    val rideEndedByRider: Boolean = false,
    /** Monotonic (elapsedRealtime) ms of the rider's "I've parked" tap, or null
     *  while [rideEndedByRider] is false. */
    val rideEndedAtMs: Long? = null,
    /** True while the eBike's last reading says locked or asleep. Written each
     *  tick by `RadarLinkCoordinator.evaluateRadarDrop` from the snapshot the
     *  drop cue reads. Not age-gated, by design: the bike drops its link as it
     *  sleeps, so a real lock reading is already old when the home screen
     *  would ask (`theBikesLockReachesTheStateTheHomeScreenAsksFrom`). A lock
     *  the bike has stopped sending is forgotten when the radar begins a ride
     *  (`aNewRideForgetsTheLastRidesLock`,
     *  `theFirstRadarOfTheSessionForgetsAnEarlierLock`); within one ride it
     *  stands until the bike sends another reading
     *  (`aReconnectWithinTheSameRideKeepsTheLock`). */
    val bikeLocked: Boolean = false,
    /** True on the tick after a reconnect that started a NEW RIDE (the radar
     *  was off longer than the app's parked boundary).
     *
     *  Exists so the drop cue's bookkeeping reset runs on the tick loop, which
     *  is that latch's only writer, rather than on the BLE callback thread. A
     *  reset there raced a tick that had computed its decision while the radar
     *  was still down and wrote the latch straight back, resurrecting the stale
     *  acknowledgement pulse the reset exists to stop. Consumed and cleared in
     *  `RadarLinkCoordinator.evaluateRadarDrop`. */
    val newRideAtConnect: Boolean = false,
) {
    /** The bike reads locked, or the rider tapped "I've parked". Read by the
     *  dead-radar banner, the drop cue's latch reset and the home screen's
     *  parked question; the cue gate takes the two separately in
     *  `RadarDropDecider.ridingConfirmed`. */
    val parked: Boolean get() = bikeLocked || rideEndedByRider
}
