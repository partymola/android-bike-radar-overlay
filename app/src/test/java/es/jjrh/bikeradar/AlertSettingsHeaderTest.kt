// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The capture header records the alert settings the ride was decided with,
 * so a replay can tell whether it is reproducing the ride or the defaults.
 */
@RunWith(RobolectricTestRunner::class)
class AlertSettingsHeaderTest {

    private val base = Prefs(ApplicationProvider.getApplicationContext<Application>()).snapshot()

    @Test
    fun `every setting the decider is called with is on the line`() {
        val s = base.copy(
            alertMaxDistanceM = 30,
            turnAwareAlertsEnabled = true,
            urgentLowSpeedEnabled = false,
            urgentUnconfidentWaitEnabled = true,
            urgentPassClearanceM = 1.8f,
            closingSpeedCeilingMs = 35,
        )
        assertEquals(
            "# settings alert_max_m=30 turn_aware=true urgent_low_speed=false" +
                " unconf_wait=true pass_clearance_m=1.8 ceiling_mps=35",
            OverlayPipeline.alertSettingsLine(s),
        )
    }

    @Test
    fun `each switch reads its own value the other way too`() {
        val s = base.copy(
            alertMaxDistanceM = 45,
            turnAwareAlertsEnabled = false,
            urgentLowSpeedEnabled = true,
            urgentUnconfidentWaitEnabled = false,
            urgentPassClearanceM = 2.5f,
            closingSpeedCeilingMs = 20,
        )
        assertEquals(
            "# settings alert_max_m=45 turn_aware=false urgent_low_speed=true" +
                " unconf_wait=false pass_clearance_m=2.5 ceiling_mps=20",
            OverlayPipeline.alertSettingsLine(s),
        )
    }

    @Test
    fun `no ceiling reads as none`() {
        val line = OverlayPipeline.alertSettingsLine(base.copy(closingSpeedCeilingMs = null))
        assertEquals("ceiling_mps=none", line.substringAfterLast(' '))
    }

    @Test
    fun `the line is not counted as a cue line`() {
        // Capture readers and OverlayPipelineDrivingTest count cues by this prefix.
        assertFalse(OverlayPipeline.alertSettingsLine(base).startsWith("# alert"))
    }
}
