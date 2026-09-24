// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one definition of "a call": it silences the beeper, shows the overlay
 * over a granted app's hold, and takes the "Overlay hidden" line off the ride
 * notification. A ringing phone is not a call, or an unanswered ring would do
 * all three.
 */
class AlertBeeperCallModeTest {

    @Test
    fun onlyATelephonyOrInternetCallCounts() {
        val expected = mapOf(
            -2 to false, // MODE_INVALID
            -1 to false, // MODE_CURRENT
            0 to false, // MODE_NORMAL
            1 to false, // MODE_RINGTONE
            2 to true, // MODE_IN_CALL
            3 to true, // MODE_IN_COMMUNICATION
            4 to false, // MODE_CALL_SCREENING
            5 to false, // MODE_CALL_REDIRECT
            6 to false, // MODE_COMMUNICATION_REDIRECT
        )
        for ((mode, isCall) in expected) {
            assertEquals("mode $mode", isCall, AlertBeeper.isCallMode(mode))
        }
    }
}
