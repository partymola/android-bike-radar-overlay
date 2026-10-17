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

/** The walk-away alarm stopped needing the overlay's camera-off warning, and
 *  no rider who could not hear it before starts hearing it unasked. */
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
        if (cameraPicked) prefs.dashcamMac = CAMERA
        prefs.dashcamWarnWhenOff = warnWhenOff
    }

    @Test
    fun aCameraWithTheWarningOffHasTheAlarmSwitchedOff() {
        upgrader(cameraPicked = true, warnWhenOff = false)
        prefs.migrateWalkAwayAlarmOffTheOverlayWarning()
        assertFalse(prefs.walkAwayAlarmEnabled)
    }

    @Test
    fun aCameraWithTheWarningOnKeepsTheAlarm() {
        upgrader(cameraPicked = true, warnWhenOff = true)
        prefs.migrateWalkAwayAlarmOffTheOverlayWarning()
        assertTrue(prefs.walkAwayAlarmEnabled)
    }

    @Test
    fun noCameraKeepsTheDefault() {
        upgrader(cameraPicked = false, warnWhenOff = false)
        prefs.migrateWalkAwayAlarmOffTheOverlayWarning()
        assertTrue(prefs.walkAwayAlarmEnabled)
    }

    @Test
    fun anInstallStillInSetupKeepsTheDefault() {
        prefs.dashcamMac = CAMERA
        prefs.migrateWalkAwayAlarmOffTheOverlayWarning()
        assertTrue(prefs.walkAwayAlarmEnabled)
    }

    @Test
    fun itRunsOnlyOnce() {
        upgrader(cameraPicked = true, warnWhenOff = false)
        prefs.migrateWalkAwayAlarmOffTheOverlayWarning()
        // The rider turns it back on; a later start must not undo that.
        prefs.walkAwayAlarmEnabled = true
        prefs.migrateWalkAwayAlarmOffTheOverlayWarning()
        assertTrue(prefs.walkAwayAlarmEnabled)
    }

    @Test
    fun aCameraPickedAfterTheFirstStartKeepsTheDefault() {
        prefs.migrateWalkAwayAlarmOffTheOverlayWarning()
        upgrader(cameraPicked = true, warnWhenOff = false)
        prefs.migrateWalkAwayAlarmOffTheOverlayWarning()
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
