// SPDX-License-Identifier: GPL-3.0-or-later
// Additional permission for cross-app consumers: additional-permission.txt
package es.jjrh.bikeradar.ipc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Which apps are asking Bike Radar to hide its own overlay.
 *
 * Held per package, and taking a hold requires a live listener registration:
 * that listener's binder is the only thing here that dies with the consumer, so
 * it is what a hold is anchored to. Without the anchor a crashed or revoked
 * consumer leaves the rider with no overlay and nothing to restore it.
 *
 * A hold is dropped when the consumer unregisters
 * (`unregisteringLiftsTheOverlayHold`), its listener's binder dies
 * (`aConsumerCrashingRestoresTheOverlay`, which is also what an uninstall
 * does), the rider revokes (`revokingLiftsThatPackagesOverlayHold`) or narrows
 * the grant to read only (`losingControlAloneLiftsTheHold`), the last consumer
 * unbinds (`everyConsumerLeavingRestoresTheOverlay`), or the service stops
 * (`stoppingTheServiceRestoresTheOverlay`).
 *
 * An uninstall reaches this through the binder death, not through
 * `RadarIpcBinder.revalidate`, which runs only on a write to the grant store
 * and an uninstall is not one. That function's uid check is a second net, not
 * the mechanism.
 *
 * Nothing lifts a hold for a consumer that is merely backgrounded, which is why
 * the contract asks it to show the overlay again when it stops drawing.
 */
object RadarOverlayGate {

    private val _hiddenBy = MutableStateFlow<Set<String>>(emptySet())

    /** Package names currently asking for the overlay to be hidden. */
    val hiddenBy: StateFlow<Set<String>> = _hiddenBy

    /** True while any consumer is asking. The overlay pipeline reads this. */
    val hidden: Boolean get() = _hiddenBy.value.isNotEmpty()

    /**
     * `update`, not `value = value + x`: holders arrive on binder threads while
     * revalidation drops them from a coroutine, and a losing interleaving
     * restores the hold of a package the rider has just revoked, with nothing
     * left to lift it. `RadarOverlayGateIsAtomicTest` reads this source and is
     * what catches one of the two being reverted alone, which no load test can.
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
