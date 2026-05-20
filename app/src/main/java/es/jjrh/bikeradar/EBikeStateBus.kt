// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide bus exposing [EBikeLink]'s outcome + snapshot to UI
 * surfaces that live outside the [BikeRadarService]. [EBikeLink] is
 * service-owned: the service builds it in [BikeRadarService.maybeStartEBikeLink]
 * and tears it down in [BikeRadarService.onDestroy]. UI Composables
 * (onboarding eBike step, Settings -> eBike) can't reach the live
 * instance, so the service mirrors its flows into this bus whenever the
 * link emits.
 *
 * Same pattern as [HaHealthBus] and [BatteryStateBus]: a single
 * MutableStateFlow per signal, kept current by the producer side, read
 * by collectors via the read-only StateFlow surface.
 *
 * When the service is not running (e.g. cold-start before
 * firstRunComplete = true, or the rider stopped the service), these
 * stay at their last-published value. The bus is cleared back to
 * [LdiOutcome.Idle] + an empty snapshot by [reset], called from
 * [BikeRadarService.onDestroy].
 */
object EBikeStateBus {
    private val _outcome = MutableStateFlow<LdiOutcome>(LdiOutcome.Idle)
    val outcome: StateFlow<LdiOutcome> = _outcome

    private val _snapshot = MutableStateFlow(LiveDataSnapshot())
    val snapshot: StateFlow<LiveDataSnapshot> = _snapshot

    fun setOutcome(value: LdiOutcome) { _outcome.value = value }
    fun setSnapshot(value: LiveDataSnapshot) { _snapshot.value = value }

    /** Restore default state. Called on service destroy so UI surfaces
     *  see a clean Idle state after the rider stops the service. */
    fun reset() {
        _outcome.value = LdiOutcome.Idle
        _snapshot.value = LiveDataSnapshot()
    }
}
