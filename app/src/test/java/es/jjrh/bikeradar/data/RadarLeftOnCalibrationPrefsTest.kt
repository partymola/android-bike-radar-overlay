// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBuild

/** Where the radar-left-on calibration is kept, and what makes it start again. */
@RunWith(RobolectricTestRunner::class)
class RadarLeftOnCalibrationPrefsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clear() {
        for (file in listOf("bike_radar_prefs", "bike_radar_learned")) {
            app.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun itIsKeptOutsideTheSettingsFileTheBackupCarries() {
        Prefs(app).setRadarLeftOnSwitchOffs(RADAR, 3)
        assertTrue(app.getSharedPreferences("bike_radar_learned", Context.MODE_PRIVATE).all.isNotEmpty())
        assertTrue(
            "nothing of it in the backed-up settings",
            app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).all.keys.none { it.startsWith("radar_left_on_cal") },
        )
    }

    @Test
    fun theBackupRulesLeaveItOutOfBoth() {
        val rules = RepoFiles.repoFile("app/src/main/res/xml/data_extraction_rules.xml").readText()
        val exclusion = Regex("""<exclude\s+domain="sharedpref"\s+path="bike_radar_learned\.xml"\s*/>""")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = rules.substringAfter("<$section").substringBefore("</$section>")
            assertTrue("$section must exclude the learned file", exclusion.containsMatchIn(body))
        }
    }

    @Test
    fun aSystemUpdateStartsTheRadarCalibrationAgain() {
        ShadowBuild.setFingerprint("phone/build:16/A")
        Prefs(app).setRadarLeftOnSwitchOffs(RADAR, 3)
        assertEquals(3, Prefs(app).radarLeftOnSwitchOffs(RADAR))
        ShadowBuild.setFingerprint("phone/build:16/B")
        assertEquals(0, Prefs(app).radarLeftOnSwitchOffs(RADAR))
    }

    @Test
    fun theBundleShowsTheCountAndNotTheRadar() {
        Prefs(app).setRadarLeftOnSwitchOffs(RADAR, 2)
        val dump = Prefs(app).dumpAll()
        assertTrue(dump, dump.contains("radar_left_on_switch_offs=2"))
        assertFalse(dump, dump.contains(RADAR))
    }

    private companion object {
        const val RADAR = "AA:BB:CC:DD:EE:01"
    }
}
