// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.Context
import android.os.PowerManager

/**
 * A bounded, non-reference-counted PARTIAL_WAKE_LOCK. The service holds three:
 * the default-tag ride lock, held only during a LIVE-RIDE off-episode (the
 * window after the rear radar drops while the rider is still moving); the
 * [RADAR_LEFT_ON_TAG] lock, held through the radar-left-on check after a
 * qualifying drop, when the rider has walked away
 * (`BikeRadarServiceSmokeTest.aRadarLeftOnDropHoldsItsOwnWakeLock`); and the
 * [WALK_AWAY_TAG] lock, taken with the dashcam walk-away alarm on and a dashcam
 * chosen, at a drop that alarm can still sound for, and held until it sounds,
 * the episode goes BLANK, the radar is back, the rider dismisses or snoozes
 * it, the radar-left-on alarm sounds or Bluetooth goes off, at most the
 * rider's threshold plus 15 s (`DashcamAlarmServiceTest`). The rest of this
 * KDoc is about the ride lock; the service sizes and releases each.
 *
 * Why it exists: a radar-only rider (no bonded dashcam, no eBike) has zero BLE
 * wakeups once the radar drops, so in deep Doze the coroutine `delay()` timers
 * that drive the dead-radar cue, the walk-away alarm and the ride-summary can
 * all sleep past their deadlines until the next maintenance window: the
 * dead-radar warning then lands minutes late. A foreground service holds no
 * wakelock of its own.
 *
 * BOUNDED, never an unbounded hold. The lock is acquired with a hard timeout
 * cap so PowerManager auto-releases it even if every explicit release path is
 * missed; the caller sizes the cap to cover the first cue plus its first repeat
 * (see `RadarLinkCoordinator.RIDE_WAKELOCK_CAP_MS`). It is also released
 * explicitly the moment the off-episode resolves (radar reconnects, walk-away
 * goes BLANK, or the ride summary posts), so the common case frees the CPU far
 * sooner than the cap. This is the escalation the manifest's old no-wakelock
 * note anticipated ("if this becomes user-visible"), scoped tightly to the
 * live-ride off-episode rather than held for the whole service lifetime.
 *
 * Not reference-counted: [acquire] and [release] are idempotent, so the several
 * release call sites can each fire without tracking who acquired. They are
 * `@Synchronized` because acquire runs on the BLE callback thread while the
 * releases fire from there AND the tick coroutine / onDestroy - the guarded
 * check-and-act must be atomic, since a non-reference-counted `release()` on an
 * already-released lock throws.
 */
internal class RideWakeLock(context: Context, tag: String = WAKE_LOCK_TAG) {

    private val wakeLock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            .apply { setReferenceCounted(false) }

    /** The cap the lock was last taken with, for a test to read: the platform
     *  lock does not report it. */
    @get:androidx.annotation.VisibleForTesting
    internal var lastCapMs: Long? = null
        private set

    /** Acquire with a hard timeout cap (ms). No-op if already held. */
    @Synchronized
    fun acquire(capMs: Long) {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(capMs)
            lastCapMs = capMs
        }
    }

    /** Release now. No-op if not held (idempotent across the release sites). */
    @Synchronized
    fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    @Synchronized
    fun isHeld(): Boolean = wakeLock.isHeld

    companion object {
        private const val WAKE_LOCK_TAG = "es.jjrh.bikeradar:ride-off-episode"

        /** The radar-left-on check's own lock: a second instance, because
         *  this one is not reference-counted and the ride's is released at
         *  BLANK (20 s in for a camera not heard, at once for one reported
         *  switched off), which a drop while riding never reaches. */
        const val RADAR_LEFT_ON_TAG = "es.jjrh.bikeradar:radar-left-on"

        /** The dashcam walk-away alarm's armed window: its own instance, as it
         *  is taken whether or not the rider was moving and released at FIRE,
         *  which the ride's lock is not. */
        const val WALK_AWAY_TAG = "es.jjrh.bikeradar:walk-away"
    }
}
