// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class HaHealth {
    object Unknown : HaHealth()
    object Ok : HaHealth()
    data class Error(val message: String, val atMs: Long = System.currentTimeMillis()) : HaHealth()
}

object HaHealthBus {
    private val _state = MutableStateFlow<HaHealth>(HaHealth.Unknown)
    val state: StateFlow<HaHealth> = _state

    fun reportOk() { _state.value = HaHealth.Ok }
    fun reportError(message: String) { _state.value = HaHealth.Error(message) }
}
