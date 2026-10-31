// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.RadarSelection
import es.jjrh.bikeradar.RadarState
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.Vehicle
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.DashcamOwnership
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The radar's battery row on the real screens, reading the real bus with a
 * front camera on it. With no pin the lookup takes the first entry whose name
 * passes, and the bus keeps insertion order, so the camera goes on first. A
 * picked radar shows the reading under its own name or none, never one under
 * another; the bus is keyed by name, so two radars sharing a name share one.
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
        BikeRadarService.macToSlug.clear()
        BatteryStateBus.clearForTest()
        BatteryStateBus.update(BatteryEntry(slug = "vue_84509", name = "Varia Vue 84509", pct = 17))
        BatteryStateBus.update(BatteryEntry(slug = "rearvue8", name = "RearVue8", pct = 78))
    }

    @After
    fun tearDown() {
        BikeRadarService.macToSlug.clear()
        BatteryStateBus.clearForTest()
        RadarStateBus.clear()
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private val home: @Composable () -> Unit = { MainScreen(navController = rememberNavController(), prefs = prefs) }
    private val settings: @Composable () -> Unit = { SettingsScreen(navController = rememberNavController(), prefs = prefs) }
    private val radarScreen: @Composable () -> Unit = { SettingsRadarDevice(navController = rememberNavController(), prefs = prefs) }

    /** A radar the name-match misses, picked, beside the one it knows. */
    private fun pickARadarNamedOnlyByTheVendorWord() {
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val radar = adapter.getRemoteDevice("AA:BB:CC:DD:EE:11").also { shadowOf(it).setName("RearVue8") }
        val picked = adapter.getRemoteDevice(PICKED).also { shadowOf(it).setName("Varia RVR315") }
        shadowOf(adapter).setBondedDevices(setOf(radar, picked))
        prefs.radarMac = PICKED
    }

    private fun aReadingFromThePick() {
        BikeRadarService.macToSlug[PICKED] = "rvr315"
        BatteryStateBus.update(BatteryEntry(slug = "rvr315", name = "Varia RVR315", pct = 63))
    }

    private fun assertRow(shown: String?, hidden: String, screen: @Composable () -> Unit) {
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
        compose.onNodeWithText(hidden, useUnmergedTree = true).assertDoesNotExist()
        // The chip shows only on a live row, so an absence means nothing without it.
        assertTrue(compose.onAllNodesWithText("Live", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        if (shown != null) compose.onNodeWithText(shown, useUnmergedTree = true).assertExists()
    }

    @Test
    fun theHomeRowShowsTheRadarsFigureNotTheCameras() = assertRow("78%", "17%", home)

    @Test
    fun theSettingsRowShowsTheRadarsFigureNotTheCameras() = assertRow("78%", "17%", settings)

    @Test
    fun theRadarScreenShowsTheRadarsFigureNotTheCameras() = assertRow("78%", "17%", radarScreen)

    @Test
    fun theHomeRowShowsAPickedRadarsOwnFigure() {
        pickARadarNamedOnlyByTheVendorWord()
        aReadingFromThePick()
        assertRow("63%", "78%", home)
    }

    @Test
    fun theSettingsRowShowsAPickedRadarsOwnFigure() {
        pickARadarNamedOnlyByTheVendorWord()
        aReadingFromThePick()
        assertRow("63%", "78%", settings)
    }

    @Test
    fun theRadarScreenShowsAPickedRadarsOwnFigure() {
        pickARadarNamedOnlyByTheVendorWord()
        aReadingFromThePick()
        assertRow("63%", "78%", radarScreen)
    }

    /** Paired no longer, with its old reading still on the bus. */
    private fun aPinGone() {
        prefs.radarMac = PICKED
        aReadingFromThePick()
    }

    @Test
    fun theHomeRowFollowsTheRadarThatLinksOnceThePinIsGone() {
        aPinGone()
        assertRow("78%", "63%", home)
    }

    @Test
    fun theSettingsRowFollowsTheRadarThatLinksOnceThePinIsGone() {
        aPinGone()
        assertRow("78%", "63%", settings)
    }

    @Test
    fun theRadarScreenFollowsTheRadarThatLinksOnceThePinIsGone() {
        aPinGone()
        assertRow("78%", "63%", radarScreen)
    }

    @Test
    fun thePairedListIsReadOnlyForAPin() {
        assertEquals(emptyList<RadarSelection.BondedRadar>(), RadarSelection.bondedForBattery(app, null))
        assertEquals(2, RadarSelection.bondedForBattery(app, PICKED).size)
    }

    @Test
    fun anEmptyBondedReadKeepsThePin() {
        BikeRadarService.macToSlug[PICKED] = "pinned"
        val entries = mapOf(
            "radar" to BatteryEntry("radar", "RearVue8", 66),
            "pinned" to BatteryEntry("pinned", "Varia RVR315", 88),
        )
        assertEquals(88, RadarSelection.radarBattery(entries, emptyList(), PICKED)?.pct)
    }

    @Test
    fun theHomeRowNeverLendsAPickedRadarAnotherRadarsFigure() {
        pickARadarNamedOnlyByTheVendorWord()
        assertRow(null, "78%", home)
    }

    @Test
    fun theSettingsRowNeverLendsAPickedRadarAnotherRadarsFigure() {
        pickARadarNamedOnlyByTheVendorWord()
        assertRow(null, "78%", settings)
    }

    @Test
    fun theRadarScreenNeverLendsAPickedRadarAnotherRadarsFigure() {
        pickARadarNamedOnlyByTheVendorWord()
        assertRow(null, "78%", radarScreen)
    }

    private companion object {
        const val PICKED = "AA:BB:CC:DD:EE:55"
    }
}
