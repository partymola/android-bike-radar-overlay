// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object ClosePassStateBus {
    private val _sessionCount = MutableStateFlow(0)
    val sessionCount: StateFlow<Int> = _sessionCount

    /** Atomic increment. `value += n` would be a read-modify-write race when
     *  concurrent callers land on the same StateFlow; `update { }` is a CAS
     *  loop. Mirrors the pattern in [BatteryStateBus.update]. */
    fun increment(n: Int = 1) {
        _sessionCount.update { it + n }
    }

    /** Called by [BikeRadarService.onCreate] so the home screen's
     *  "this ride" counter starts at 0 each time the service launches.
     *  Without this, the counter accumulates across rides within a single
     *  process lifetime and the label is misleading. */
    fun reset() {
        _sessionCount.value = 0
    }
}
