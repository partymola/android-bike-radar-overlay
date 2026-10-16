// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [RadarSelection.pin] is the one way a radar is pinned, from Settings and setup alike. */
@RunWith(RobolectricTestRunner::class)
class RadarSelectionPinTest {

    private val prefs = Prefs(ApplicationProvider.getApplicationContext<Application>())

    @Test fun `pinning a different radar forgets the old one's firmware`() {
        prefs.radarMac = "AA:AA:AA:AA:AA:AA"
        prefs.radarFirmwareRev = "6.70"

        RadarSelection.pin(prefs, RadarSelection.BondedRadar("BB:BB:BB:BB:BB:BB", "RTL515"))

        assertEquals("BB:BB:BB:BB:BB:BB", prefs.radarMac)
        assertEquals("RTL515", prefs.radarDisplayName)
        assertNull(prefs.radarFirmwareRev)
    }

    @Test fun `pinning is one change`() {
        prefs.radarMac = "AA:AA:AA:AA:AA:AA"
        prefs.radarDisplayName = "RearVue8"
        val sp = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("bike_radar_prefs", android.content.Context.MODE_PRIVATE)
        val seen = mutableListOf<Pair<String?, String?>>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            seen += prefs.radarMac to prefs.radarDisplayName
        }
        sp.registerOnSharedPreferenceChangeListener(listener)

        RadarSelection.pin(prefs, RadarSelection.BondedRadar("BB:BB:BB:BB:BB:BB", "RTL515"))

        sp.unregisterOnSharedPreferenceChangeListener(listener)
        assertEquals("BB:BB:BB:BB:BB:BB" to "RTL515", seen.first())
    }

    @Test fun `re-pinning the same radar keeps its firmware`() {
        prefs.radarMac = "AA:AA:AA:AA:AA:AA"
        prefs.radarFirmwareRev = "6.70"

        RadarSelection.pin(prefs, RadarSelection.BondedRadar("aa:aa:aa:aa:aa:aa", "RearVue8"))

        assertEquals("6.70", prefs.radarFirmwareRev)
    }
}
