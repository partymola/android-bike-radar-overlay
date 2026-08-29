// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How recent a published [RadarState] has to be for a screen to call the radar
 * live. One constant because two surfaces ask the same question: the home
 * System card and the Settings radar card. They used to answer it differently,
 * which is how the same radar could read Live on one screen and not on the
 * other.
 *
 * Wall clock on both sides, matching [RadarState.timestamp].
 */
const val RADAR_FRAME_FRESH_MS = 10_000L

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
