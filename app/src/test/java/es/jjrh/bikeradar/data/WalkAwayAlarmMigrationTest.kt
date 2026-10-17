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

/** The walk-away alarm stopped needing the overlay's camera-off warning and
 *  the radar-left-on alarm arrived; no upgrader who could not hear an alarm
 *  before starts hearing one unasked. */
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
    fun aCameraSwitchedOffWithItsPickKeptCountsAsNoneForTheRadarAlarm() {
        upgrader(cameraPicked = true, warnWhenOff = false)
        prefs.dashcamOwnership = DashcamOwnership.NO
        prefs.migrateWalkAwayAlarms()
        assertTrue(prefs.radarLeftOnAlarmEnabled)
        // Switched back on, the camera stays as silent as it was.
        assertFalse(prefs.walkAwayAlarmEnabled)
    }

    @Test
    fun aCameraWithTheWarningOffHasBothAlarmsOff() {
        upgrader(cameraPicked = true, warnWhenOff = false)
        prefs.migrateWalkAwayAlarms()
        assertFalse(prefs.walkAwayAlarmEnabled)
        assertFalse(prefs.radarLeftOnAlarmEnabled)
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
    fun anInstallStillInSetupKeepsTheDefaults() {
        // A camera picked with the warning off would switch both off past setup.
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
        // The rider turns them back on; a later start must not undo that.
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
