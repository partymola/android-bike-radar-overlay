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
 * Whether [state] counts as a live radar for a screen reading it at [nowMs]
 * (wall clock).
 *
 * ANY decoding source counts, not the modern one specifically: a radar on the
 * legacy stream is delivering targets, and a screen that asks "is it V2"
 * instead of "is it live" denies data the overlay is already drawing. The two
 * surfaces that ask this shared a constant but each spelled the test out, so
 * the shape could still drift apart even while the number agreed - which is
 * the same defect the constant was introduced to remove, one level up.
 */
fun radarStreamIsLive(state: RadarState, nowMs: Long): Boolean = state.source != DataSource.NONE && nowMs - state.timestamp < RADAR_FRAME_FRESH_MS

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
