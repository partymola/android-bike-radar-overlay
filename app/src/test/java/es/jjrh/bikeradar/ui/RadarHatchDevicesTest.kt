// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.RadarSelection.BondedRadar
import org.junit.Assert.assertEquals
import org.junit.Test

/** [radarHatchDevices] judges the bonded list it is given, with no second
 *  read of the adapter. */
class RadarHatchDevicesTest {

    private val radar = BondedRadar("AA:BB:CC:DD:EE:01", "RearVue8")
    private val watch = BondedRadar("AA:BB:CC:DD:EE:02", "Watch")
    private val bike = BondedRadar("AA:BB:CC:DD:EE:03", "smart system eBike")
    private val cam = BondedRadar("AA:BB:CC:DD:EE:04", "Front cam")

    @Test
    fun theEBikeAndTheDashcamAreLeftOutOfTheHatch() {
        assertEquals(listOf(watch), radarHatchDevices(listOf(radar, watch, bike, cam), dashcamMac = cam.mac.lowercase()))
    }

    @Test
    fun anEBikeByEitherHalfOfItsNameIsLeftOutToo() {
        val byEBike = BondedRadar("AA:BB:CC:DD:EE:05", "My eBike")
        val bySmartSystem = BondedRadar("AA:BB:CC:DD:EE:06", "BOSCH SMART SYSTEM")
        assertEquals(listOf(watch), radarHatchDevices(listOf(watch, byEBike), dashcamMac = null))
        assertEquals(listOf(watch), radarHatchDevices(listOf(watch, bySmartSystem), dashcamMac = null))
    }

    @Test
    fun withNoEBikeEveryOtherDeviceIsOffered() {
        assertEquals(listOf(watch, cam), radarHatchDevices(listOf(radar, watch, cam), dashcamMac = null))
    }
}
