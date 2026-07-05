// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the [Prefs.alertBeeperSavedAlarmVolume] round-trip: the crash-repair
 * slot AlertBeeper's media-volume floor uses to persist the rider's alarm
 * level while a cue burst lifts it. A leak (a set value surviving a restore)
 * or a lost write would leave the alarm slider stranded high, so the null /
 * non-null branches on both accessors are pinned here.
 */
@RunWith(RobolectricTestRunner::class)
class AlertBeeperAlarmVolumePrefsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearPrefs() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test fun defaultsToNull_whenNoLiftInFlight() {
        assertNull(Prefs(app).alertBeeperSavedAlarmVolume)
    }

    @Test fun persistsAndClearsTheSavedLevel() {
        val prefs = Prefs(app)
        prefs.alertBeeperSavedAlarmVolume = 4
        assertEquals(4, prefs.alertBeeperSavedAlarmVolume)
        // A fresh instance reads it back - this is the mid-lift process-death repair path.
        assertEquals(4, Prefs(app).alertBeeperSavedAlarmVolume)
        prefs.alertBeeperSavedAlarmVolume = null
        assertNull("clearing the slot must remove it", prefs.alertBeeperSavedAlarmVolume)
    }
}
