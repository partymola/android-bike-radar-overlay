// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.EBikeOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * With Nearby devices denied, every screen that describes the dashcam or the
 * eBike gives the same answer as the home row that opens it: no "No signal"
 * for a camera the app cannot reach, no "Waiting for Flow" for a reader that
 * never started ([radioUsable], [shownEBikeStage]). A dashcam and eBike data
 * are set up in every test, so a screen that ignores the permission has
 * something to show.
 */
@RunWith(AndroidJUnit4::class)
class NearbyDeniedScreensAgreeTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        prefs = Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
            dashcamOwnership = DashcamOwnership.YES
            dashcamMac = "AA:BB:CC:DD:EE:22"
            dashcamDisplayName = "Front cam"
            eBikeOwnership = EBikeOwnership.YES
            eBikeDataEnabled = true
        }
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val radar = adapter.getRemoteDevice("AA:BB:CC:DD:EE:11")
        shadowOf(radar).setName("RearVue8")
        shadowOf(adapter).setBondedDevices(setOf(radar))
        RadarStateBus.clear()
    }

    @After
    fun tearDown() {
        RadarStateBus.clear()
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun show(screen: @Composable () -> Unit) {
        compose.setContent { screen() }
        compose.waitForIdle()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    private fun assertBlamesNothingElse() {
        assertEquals("No signal", 0, count("No signal"))
        assertEquals("Waiting for Flow", 0, count("Waiting for Flow"))
    }

    @Test
    fun theHomeScreen() {
        show { MainScreen(navController = rememberNavController(), prefs = prefs) }
        assertBlamesNothingElse()
        assertTrue(count("Needs Nearby devices") > 0)
    }

    @Test
    fun settingsHome() {
        show { SettingsScreen(navController = rememberNavController(), prefs = prefs) }
        assertBlamesNothingElse()
        assertTrue(count("Needs Nearby devices") > 0)
    }

    @Test
    fun theDashcamScreen() {
        show { SettingsDashcam(navController = rememberNavController(), prefs = prefs) }
        assertBlamesNothingElse()
        assertTrue(count("Not paired") > 0)
    }

    @Test
    fun theEBikeScreen() {
        show { SettingsEBike(navController = rememberNavController(), prefs = prefs) }
        assertBlamesNothingElse()
        assertTrue(count("Needs Nearby devices") > 0)
        assertTrue(count("Grant Nearby devices in Settings") > 0)
    }

    @Test
    fun withNearbyDevicesTheSameScreensReportTheLink() {
        // The control: granted, the dashcam reads "No signal" again, so the
        // tests above are not passing on a screen that never says it.
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        show { SettingsDashcam(navController = rememberNavController(), prefs = prefs) }
        assertTrue(count("No signal") > 0)
    }
}
