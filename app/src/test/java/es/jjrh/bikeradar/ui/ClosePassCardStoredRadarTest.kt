// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.ClosePassStateBus
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.RadarState
import es.jjrh.bikeradar.RadarStateBus
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
import org.robolectric.annotation.Config

/**
 * The real home screen's close-pass card for a rider with counting on, no
 * front camera and no eBike, whose radar reports distance only: what it says
 * while that radar streams, it says while no radar streams.
 */
@RunWith(RobolectricTestRunner::class)
class ClosePassCardStoredRadarTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
            pausedUntilEpochMs = 0L
            dashcamOwnership = DashcamOwnership.NO
            closePassLoggingEnabled = true
        }
        shadowOf(adapter).setEnabled(true)
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        paired(radar(RADAR_A))
        ClosePassStateBus.reset()
        RadarStateBus.clear()
    }

    @After
    fun tearDown() {
        ClosePassStateBus.reset()
        RadarStateBus.clear()
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun radar(mac: String): BluetoothDevice = adapter.getRemoteDevice(mac).also { shadowOf(it).setName("RearVue8") }

    private fun paired(vararg devices: BluetoothDevice) = shadowOf(adapter).setBondedDevices(devices.toSet())

    private fun home(streaming: DataSource? = null) {
        compose.setContent { MainScreen(navController = rememberNavController(), prefs = prefs) }
        if (streaming != null) {
            RadarStateBus.publish(RadarState(vehicles = emptyList(), timestamp = System.currentTimeMillis(), source = streaming))
        }
        compose.waitForIdle()
    }

    private fun assertSaysDistanceOnly() {
        compose.onAllNodesWithContentDescription("0 close passes this ride").assertCountEquals(0)
        compose.onAllNodesWithText("this ride").assertCountEquals(0)
        compose.onAllNodesWithText("This radar reports distance only, so it can't count close passes.").assertCountEquals(1)
    }

    private fun assertShowsTheCount() {
        compose.onAllNodesWithContentDescription("0 close passes this ride").assertCountEquals(1)
        compose.onAllNodesWithText("this ride").assertCountEquals(1)
        compose.onAllNodesWithText("can't count close passes", substring = true).assertCountEquals(0)
    }

    @Test
    fun whileADistanceOnlyRadarStreamsTheCardSaysSo() {
        home(streaming = DataSource.V1)
        assertSaysDistanceOnly()
    }

    @Test
    fun aRememberedDistanceOnlyRadarSaysTheSameWithNoRadarStreaming() {
        prefs.rangeOnlyRadarMac = RADAR_A.lowercase()
        home()
        assertSaysDistanceOnly()
    }

    @Test
    @Config(qualifiers = "land")
    fun theLandscapeLayoutSaysTheSame() {
        prefs.rangeOnlyRadarMac = RADAR_A
        home()
        assertSaysDistanceOnly()
    }

    @Test
    fun withNoRadarRememberedTheCardShowsItsCount() {
        home()
        assertShowsTheCount()
    }

    @Test
    fun aRememberedRadarIsIgnoredWhileAFullRadarStreams() {
        prefs.rangeOnlyRadarMac = RADAR_A
        home(streaming = DataSource.V2)
        assertShowsTheCount()
    }

    @Test
    fun aRememberedRadarNoLongerPairedShowsTheCount() {
        prefs.rangeOnlyRadarMac = RADAR_B
        home()
        assertShowsTheCount()
    }

    @Test
    fun aRememberedRadarUnpairedWhileTheScreenIsOpenShowsTheCount() {
        prefs.rangeOnlyRadarMac = RADAR_A
        home()
        assertSaysDistanceOnly()

        paired(radar(RADAR_B))
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        assertShowsTheCount()
    }

    @Test
    fun aRememberedRadarOtherThanThePickedOneShowsTheCount() {
        paired(radar(RADAR_A), radar(RADAR_B))
        prefs.radarMac = RADAR_A
        prefs.rangeOnlyRadarMac = RADAR_B
        home()
        assertShowsTheCount()
    }

    @Test
    fun aRememberedRadarThatIsThePickedOneSaysDistanceOnly() {
        paired(radar(RADAR_A), radar(RADAR_B))
        prefs.radarMac = RADAR_B
        prefs.rangeOnlyRadarMac = RADAR_B
        home()
        assertSaysDistanceOnly()
    }

    @Test
    fun aPairedListThatReadsEmptyKeepsTheRememberedRadar() {
        paired()
        prefs.rangeOnlyRadarMac = RADAR_A
        home()
        assertSaysDistanceOnly()
    }

    // Two radars paired and none picked. A full radar's stream empties the
    // slot (`RadarLinkControllerHarnessTest.aV2FrameFromAnotherRadarForgetsTheRememberedOne`).

    @Test
    fun withTwoPairedAndTheFullRadarStreamingLastTheCardShowsItsCount() {
        paired(radar(RADAR_A), radar(RADAR_B))
        home()
        assertShowsTheCount()
    }

    @Test
    fun withTwoPairedAndTheDistanceOnlyRadarStreamingLastTheCardSaysSo() {
        paired(radar(RADAR_A), radar(RADAR_B))
        prefs.rangeOnlyRadarMac = RADAR_A
        home()
        assertSaysDistanceOnly()
    }

    @Test
    fun aRememberedRadarThatIsAPickNoLongerPairedShowsTheCount() {
        prefs.radarMac = RADAR_B
        prefs.rangeOnlyRadarMac = RADAR_B
        home()
        assertShowsTheCount()
    }

    private companion object {
        const val RADAR_A = "AA:BB:CC:DD:EE:11"
        const val RADAR_B = "AA:BB:CC:DD:EE:22"
    }
}
