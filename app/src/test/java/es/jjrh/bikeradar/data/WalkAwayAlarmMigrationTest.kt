// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.BikeRadarApp
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The walk-away alarm stopped needing the overlay's camera-off warning, so
 *  no upgrader who could not hear it before starts hearing it unasked. The
 *  radar-left-on alarm arrived, on for every upgrader who had not switched
 *  the walk-away alarm off. */
@RunWith(RobolectricTestRunner::class)
class WalkAwayAlarmMigrationTest {

    private val app: BikeRadarApp = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    @After
    fun clear() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(app)
    }

    private fun upgrader(cameraPicked: Boolean, warnWhenOff: Boolean) {
        prefs.firstRunComplete = true
        if (cameraPicked) {
            prefs.dashcamOwnership = DashcamOwnership.YES
            prefs.dashcamMac = CAMERA
        }
        prefs.dashcamWarnWhenOff = warnWhenOff
    }

    @Test
    fun aCameraSwitchedOffWithItsPickKeptStaysSilentWhenSwitchedBackOn() {
        upgrader(cameraPicked = true, warnWhenOff = false)
        prefs.dashcamOwnership = DashcamOwnership.NO
        prefs.migrateWalkAwayAlarms()
        assertTrue(prefs.radarLeftOnAlarmEnabled)
        prefs.dashcamOwnership = DashcamOwnership.YES
        assertFalse(prefs.walkAwayAlarmEnabled)
    }

    @Test
    fun aCameraWithTheWarningOffGetsTheRadarAlarmButNotTheDashcamOne() {
        upgrader(cameraPicked = true, warnWhenOff = false)
        prefs.migrateWalkAwayAlarms()
        assertFalse(prefs.walkAwayAlarmEnabled)
        assertTrue(prefs.radarLeftOnAlarmEnabled)
    }

    @Test
    fun aCameraWithTheWarningOnKeepsBoth() {
        upgrader(cameraPicked = true, warnWhenOff = true)
        prefs.migrateWalkAwayAlarms()
        assertTrue(prefs.walkAwayAlarmEnabled)
        assertTrue(prefs.radarLeftOnAlarmEnabled)
    }

    @Test
    fun noCameraKeepsTheDefaults() {
        upgrader(cameraPicked = false, warnWhenOff = false)
        prefs.migrateWalkAwayAlarms()
        assertTrue(prefs.walkAwayAlarmEnabled)
        assertTrue(prefs.radarLeftOnAlarmEnabled)
    }

    @Test
    fun aRiderWhoSwitchedTheWalkAwayAlarmOffGetsNoRadarAlarm() {
        upgrader(cameraPicked = true, warnWhenOff = true)
        prefs.walkAwayAlarmEnabled = false
        prefs.migrateWalkAwayAlarms()
        assertFalse(prefs.radarLeftOnAlarmEnabled)
    }

    @Test
    fun aRiderWithNoCameraWhoSwitchedTheWalkAwayAlarmOffGetsNoRadarAlarm() {
        upgrader(cameraPicked = false, warnWhenOff = false)
        prefs.walkAwayAlarmEnabled = false
        prefs.migrateWalkAwayAlarms()
        assertFalse(prefs.radarLeftOnAlarmEnabled)
    }

    @Test
    fun anInstallStillInSetupKeepsTheDefaults() {
        // A camera picked with the warning off would switch the dashcam alarm off past setup.
        prefs.dashcamMac = CAMERA
        prefs.dashcamWarnWhenOff = false
        prefs.migrateWalkAwayAlarms()
        assertTrue(prefs.walkAwayAlarmEnabled)
        assertTrue(prefs.radarLeftOnAlarmEnabled)
    }

    @Test
    fun itRunsOnlyOnce() {
        upgrader(cameraPicked = true, warnWhenOff = false)
        prefs.migrateWalkAwayAlarms()
        // The rider turns it back on; a later start must not undo that.
        prefs.walkAwayAlarmEnabled = true
        prefs.migrateWalkAwayAlarms()
        assertTrue(prefs.walkAwayAlarmEnabled)
    }

    @Test
    fun aCameraPickedAfterTheFirstStartKeepsTheDefault() {
        prefs.migrateWalkAwayAlarms()
        upgrader(cameraPicked = true, warnWhenOff = false)
        prefs.migrateWalkAwayAlarms()
        assertTrue(prefs.walkAwayAlarmEnabled)
    }

    @Test
    fun theAppStartRunsIt() {
        upgrader(cameraPicked = true, warnWhenOff = false)
        app.onCreate()
        assertFalse(Prefs(app).walkAwayAlarmEnabled)
    }

    private companion object {
        const val CAMERA = "AA:BB:CC:DD:EE:02"
    }
}
