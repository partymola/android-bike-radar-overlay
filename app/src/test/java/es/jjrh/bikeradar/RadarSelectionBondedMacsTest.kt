// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.ui.PairedListNearbyDeniedTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** [RadarSelection.bondedMacs], the read every link gate takes its paired list
 *  from. The shadow throws from `getBondedDevices` without Connect, as Android
 *  does, and is set for the whole class. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [PairedListNearbyDeniedTest.BondedReadAsOnAndroid::class])
class RadarSelectionBondedMacsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val adapter by lazy { (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }

    private fun pairRadarAndWatch() {
        shadowOf(adapter).setEnabled(true)
        val radar = adapter.getRemoteDevice("AA:BB:CC:DD:EE:11").also { shadowOf(it).setName("RearVue8") }
        val watch = adapter.getRemoteDevice("AA:BB:CC:DD:EE:44").also { shadowOf(it).setName("Watch") }
        shadowOf(adapter).setBondedDevices(setOf(radar, watch))
    }

    @Test
    fun theReadGivesEveryPairedAddress() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        pairRadarAndWatch()
        assertEquals(setOf("AA:BB:CC:DD:EE:11", "AA:BB:CC:DD:EE:44"), RadarSelection.bondedMacs(app))
    }

    @Test
    fun aRefusedReadIsEmptyWhichTheGatesReadAsCannotTell() {
        // A refused read must never come back as part of the list: empty is
        // what lets a pinned radar link and replaces nothing.
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        pairRadarAndWatch()
        assertThrows("the read itself must be the one refusing", SecurityException::class.java) { adapter.bondedDevices }
        assertEquals(emptySet<String>(), RadarSelection.bondedMacs(app))
    }
}
