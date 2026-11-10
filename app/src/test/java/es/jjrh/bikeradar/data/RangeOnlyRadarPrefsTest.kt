// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which radar is remembered as distance-only, and what forgets it. */
@RunWith(RobolectricTestRunner::class)
class RangeOnlyRadarPrefsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clear() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun noRadarIsRememberedUntilOneIsStored() {
        assertNull(Prefs(app).rangeOnlyRadarMac)
    }

    @Test
    fun aStoredRadarIsReadBackByAnotherInstance() {
        Prefs(app).rangeOnlyRadarMac = "AA:BB:CC:DD:EE:01"
        assertEquals("AA:BB:CC:DD:EE:01", Prefs(app).rangeOnlyRadarMac)
    }

    @Test
    fun clearingItForgetsIt() {
        Prefs(app).rangeOnlyRadarMac = "AA:BB:CC:DD:EE:01"
        Prefs(app).rangeOnlyRadarMac = null
        assertNull(Prefs(app).rangeOnlyRadarMac)
    }

    @Test
    fun oneRadarIsRememberedAtATime() {
        Prefs(app).rangeOnlyRadarMac = "AA:BB:CC:DD:EE:01"
        Prefs(app).rangeOnlyRadarMac = "AA:BB:CC:DD:EE:02"
        assertEquals("AA:BB:CC:DD:EE:02", Prefs(app).rangeOnlyRadarMac)
    }

    @Test
    fun theBundleSaysOneIsRememberedAndNotItsAddress() {
        assertTrue(Prefs(app).dumpAll().contains("range_only_radar_mac=<unset>"))
        Prefs(app).rangeOnlyRadarMac = "AA:BB:CC:DD:EE:01"
        val dump = Prefs(app).dumpAll()
        assertTrue(dump, dump.contains("range_only_radar_mac=<redacted>"))
        assertFalse(dump, dump.contains("AA:BB:CC:DD:EE:01"))
    }
}
