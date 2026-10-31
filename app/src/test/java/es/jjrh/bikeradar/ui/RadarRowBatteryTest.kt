// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.RadarState
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.Vehicle
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The radar's battery row on the real screens, reading the real bus with a
 * front camera on it. The lookups take the first entry whose name passes, and
 * the bus keeps insertion order, so the camera goes on first.
 */
@RunWith(RobolectricTestRunner::class)
class RadarRowBatteryTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        prefs = Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
            pausedUntilEpochMs = 0L
            dashcamOwnership = DashcamOwnership.NO
        }
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        val radar = adapter.getRemoteDevice("AA:BB:CC:DD:EE:11").also { shadowOf(it).setName("RearVue8") }
        val camera = adapter.getRemoteDevice("AA:BB:CC:DD:EE:44").also { shadowOf(it).setName("Varia Vue 84509") }
        shadowOf(adapter).setBondedDevices(setOf(radar, camera))
        BatteryStateBus.clearForTest()
        BatteryStateBus.update(BatteryEntry(slug = "vue_84509", name = "Varia Vue 84509", pct = 17))
        BatteryStateBus.update(BatteryEntry(slug = "rearvue8", name = "RearVue8", pct = 78))
    }

    @After
    fun tearDown() {
        BatteryStateBus.clearForTest()
        RadarStateBus.clear()
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun assertTheRadarsFigureOn(screen: @Composable () -> Unit) {
        compose.setContent(screen)
        // After the first composition, which can outlast the row's live window.
        RadarStateBus.publish(
            RadarState(
                vehicles = listOf(Vehicle(id = 1, distanceM = 20, speedMs = -4f)),
                timestamp = System.currentTimeMillis(),
                source = DataSource.V2,
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText("17%", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("78%", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theHomeRowShowsTheRadarsFigureNotTheCameras() {
        assertTheRadarsFigureOn { MainScreen(navController = rememberNavController(), prefs = prefs) }
    }

    @Test
    fun theSettingsRowShowsTheRadarsFigureNotTheCameras() {
        assertTheRadarsFigureOn { SettingsScreen(navController = rememberNavController(), prefs = prefs) }
    }

    @Test
    fun theRadarScreenShowsTheRadarsFigureNotTheCameras() {
        assertTheRadarsFigureOn { SettingsRadarDevice(navController = rememberNavController(), prefs = prefs) }
    }
}
