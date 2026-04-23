// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object DevModeState {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    fun loadFrom(prefs: Prefs) {
        _unlocked.value = prefs.devModeUnlocked
    }

    fun unlock(prefs: Prefs) {
        prefs.devModeUnlocked = true
        _unlocked.value = true
    }

    fun lock(prefs: Prefs) {
        prefs.devModeUnlocked = false
        _unlocked.value = false
    }
}
