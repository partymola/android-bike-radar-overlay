// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Whether another app has asked Bike Radar to hide its own overlay.
 *
 * A consumer drawing its own map wants ours out of the way, so the contract
 * lets it ask. The whole design problem is what happens when it stops asking
 * without saying so: a hide that outlives the consumer leaves the rider with
 * no overlay and nothing on screen explaining why, on a safety surface.
 *
 * So the hide is held against the ASKING PACKAGE rather than as a bare
 * boolean, and taking one requires a live listener registration. The
 * listener's binder is the only thing here that dies when the consumer's
 * process does, so it is what the hold is anchored to. Requiring it is what
 * makes the list below hold: `onUnbind` fires only when the LAST client goes,
 * and the death recipient fires only for a registered listener, so without the
 * anchor a control-only consumer could hide the overlay and then crash or be
 * revoked with nothing left to lift it.
 *
 * Each way a hold is dropped, and the test that pins it:
 *  - the consumer unregisters: `unregisteringLiftsTheOverlayHold`
 *  - its listener's binder dies: `aConsumerCrashingRestoresTheOverlay`
 *  - the rider revokes: `revokingLiftsThatPackagesOverlayHold`
 *  - the rider narrows the grant to read only: `losingControlAloneLiftsTheHold`
 *  - the last consumer unbinds: `everyConsumerLeavingRestoresTheOverlay`
 *  - the service stops: `stoppingTheServiceRestoresTheOverlay`
 *
 * An uninstall is the second of those: the consumer's process goes with it, so
 * the death recipient fires. Nothing here watches for a package being removed,
 * and `RadarIpcBinder.revalidate` only runs on a write to the grant store,
 * which an uninstall is not - its uid check is a second net for a package that
 * is somehow still in the registry at the next revalidation, not the mechanism.
 *
 * Every one of those is the consumer going away or the rider intervening.
 * Nothing here lifts a hold for a consumer that is simply backgrounded and
 * still bound, which is why the contract asks a consumer to show the overlay
 * again when it stops drawing.
 *
 * Nothing here is a rider preference. There is no in-app setting to hide the
 * overlay, so this cannot contradict one. That is a statement about today's
 * code and nothing fails if it stops being true; a setting added later wins,
 * and the rule then belongs here with a test.
 */
object RadarOverlayGate {

    private val _hiddenBy = MutableStateFlow<Set<String>>(emptySet())

    /** Package names currently asking for the overlay to be hidden. */
    val hiddenBy: StateFlow<Set<String>> = _hiddenBy

    /** True while any consumer is asking. The overlay pipeline reads this. */
    val hidden: Boolean get() = _hiddenBy.value.isNotEmpty()

    /**
     * `update`, not `value = value + x`. The plain form is a read, a compute
     * and a write with no atomicity, and holders arrive from binder pool
     * threads while the app's own revalidation drops them from a coroutine, so
     * two of these overlap in ordinary use.
     *
     * One losing interleaving is not benign: a hide computed from a stale set
     * writes back a package the rider has just had removed, restoring the hold
     * of an app that no longer has the grant and does not know it is holding.
     * Nothing then lifts it until the next write to the grant store.
     * Two tests, because one is not enough. `concurrentHidesAndShowsNeverResurrectAHold`
     * reaches the race under load, but only when BOTH of these are plain: with
     * one still atomic its retry covers the other's gap and no load finds it.
     * `RadarOverlayGateIsAtomicTest` reads this source, and is what catches one
     * of the two being reverted on its own.
     */
    fun hide(packageName: String) {
        _hiddenBy.update { it + packageName }
    }

    fun show(packageName: String) {
        _hiddenBy.update { it - packageName }
    }

    /**
     * Drop every hold. For service teardown, where no consumer survives to
     * lift its own, and for tests, which would otherwise leak process-global
     * state into each other.
     */
    fun reset() {
        _hiddenBy.value = emptySet()
    }
}
