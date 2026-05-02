// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ClosePassStateBus {
    private val _sessionCount = MutableStateFlow(0)
    val sessionCount: StateFlow<Int> = _sessionCount

    fun increment(n: Int = 1) { _sessionCount.value += n }

    /** Called by [BikeRadarService.onCreate] so the home screen's
     *  "this ride" counter starts at 0 each time the service launches.
     *  Without this, the counter accumulates across rides within a single
     *  process lifetime and the label is misleading. */
    fun reset() { _sessionCount.value = 0 }
}
