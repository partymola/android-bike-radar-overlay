// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide radar state, published by the live BLE link service and
 * consumed by the overlay service. Using a simple singleton StateFlow so we
 * don't need IBinder plumbing between the two foreground services.
 */
object RadarStateBus {
    private val _state = MutableStateFlow(RadarState())
    val state: StateFlow<RadarState> = _state

    fun publish(next: RadarState) {
        _state.value = next
    }

    fun clear() {
        _state.value = RadarState(timestamp = 0L)
    }
}
