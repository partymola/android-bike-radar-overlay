// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Context
import android.os.PowerManager

/**
 * A bounded PARTIAL_WAKE_LOCK held only during a LIVE-RIDE off-episode - the
 * window after the rear radar drops while the rider is still moving.
 *
 * Why it exists: a radar-only rider (no bonded dashcam, no eBike) has zero BLE
 * wakeups once the radar drops, so in deep Doze the coroutine `delay()` timers
 * that drive the dead-radar cue, the walk-away alarm and the ride-summary can
 * all sleep past their deadlines until the next maintenance window - the
 * dead-radar warning then lands minutes late. A dashcam-bonded rider is covered
 * by that probe loop's own wakeups; this lock is the insurance for the rider
 * who has neither accessory. The FGS's implicit wakelock only holds while the
 * device is awake or dozing-light, not deep-Doze.
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
internal class RideWakeLock(context: Context) {

    private val wakeLock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }

    /** Acquire with a hard timeout cap (ms). No-op if already held. */
    @Synchronized
    fun acquire(capMs: Long) {
        if (!wakeLock.isHeld) wakeLock.acquire(capMs)
    }

    /** Release now. No-op if not held (idempotent across the release sites). */
    @Synchronized
    fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    @Synchronized
    fun isHeld(): Boolean = wakeLock.isHeld

    private companion object {
        const val WAKE_LOCK_TAG = "es.jjrh.bikeradar:ride-off-episode"
    }
}
