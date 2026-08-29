// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.RadarLinkState
import es.jjrh.bikeradar.RadarState
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.Vehicle
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Composes the real [SettingsRadarDevice] wrapper against a shadow-bonded
 * radar - the goldens render only the stateless leaf, so only this test
 * exercises the wiring from the service's published link state to the card.
 * The claim under test: a radar the service holds a live GATT link to reads
 * "Connecting…", never the old blanket "Not in range" - and with the service
 * down, "Not in range" is what renders, because then it is true.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRadarDeviceStatusTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        prefs = Prefs(app)
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val radar = adapter.getRemoteDevice("AA:BB:CC:DD:EE:11")
        shadowOf(radar).setName("RearVue8")
        shadowOf(adapter).setBondedDevices(setOf(radar))
    }

    @After
    fun tearDown() {
        BikeRadarService.radarLinkStateForUi = null
        BatteryStateBus.clearForTest()
        RadarStateBus.clear()
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun showScreen() {
        composeRule.setContent {
            val nav = rememberNavController()
            SettingsRadarDevice(navController = nav, prefs = prefs)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun liveLinkWithoutDataYetReadsConnecting() {
        BikeRadarService.radarLinkStateForUi =
            MutableStateFlow(RadarLinkState(radarGattActive = true))

        showScreen()

        composeRule.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test
    fun serviceDownReadsNotInRange() {
        BikeRadarService.radarLinkStateForUi = null

        showScreen()

        composeRule.onNodeWithText("Not in range").assertIsDisplayed()
    }

    @Test
    fun aRecentDropReadsConnectingThroughTheBridge() {
        // Pins the offSinceMs wiring AND the clock choice: the deriver takes
        // elapsedRealtime, and under Robolectric that is small, so a mutant
        // passing the wall-clock tick here computes a huge age and goes red.
        BikeRadarService.radarLinkStateForUi = MutableStateFlow(
            RadarLinkState(
                radarGattActive = false,
                radarOffSinceMs = android.os.SystemClock.elapsedRealtime() - 1_000,
            ),
        )

        showScreen()

        composeRule.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test
    fun aDropOlderThanTheBridgeReadsNotInRange() {
        // The exit from CONNECTING, which the entry tests above cannot see. A
        // widened bridge window, or a wiring that ignores offSinceMs entirely
        // and treats any published state as "connecting", leaves the card
        // saying "Connecting…" for a radar that was switched off - the same
        // class of lie the tri-state exists to remove, pointed the other way.
        BikeRadarService.radarLinkStateForUi = MutableStateFlow(
            RadarLinkState(
                radarGattActive = false,
                radarOffSinceMs = android.os.SystemClock.elapsedRealtime() - 6_000,
            ),
        )

        showScreen()

        composeRule.onNodeWithText("Not in range").assertIsDisplayed()
    }

    @Test
    fun aServiceStartAfterCompositionIsPickedUpByTheTick() {
        // The static is not Compose state, so nothing invalidates on the swap
        // itself; the 5 s tick is the only thing that re-reads it, and the
        // hoisted tick read is what keeps that true when no battery entry
        // exists. This is the freeze-forever mutant's grave: revert the hoist
        // and the screen never notices the service appearing.
        BikeRadarService.radarLinkStateForUi = null

        showScreen()
        composeRule.onNodeWithText("Not in range").assertIsDisplayed()

        BikeRadarService.radarLinkStateForUi =
            MutableStateFlow(RadarLinkState(radarGattActive = true))
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test
    fun aFreshBatteryAloneDoesNotReadConnected() {
        // This replaces a test that asserted the opposite, and the reason is
        // the point: a battery reading used to be evidence of data flowing,
        // because only a live decode loop refreshed it. The setup sequence now
        // publishes one on every attempt that reaches its battery step, so an
        // aborting radar retrying every 1.5 s keeps a reading permanently
        // fresh while sending no targets at all. Under the old rule that radar
        // read Connected, which is a worse lie than either state the tri-state
        // was built to choose between.
        BikeRadarService.radarLinkStateForUi =
            MutableStateFlow(RadarLinkState(radarGattActive = true))
        BatteryStateBus.update(BatteryEntry(slug = "rearvue8", name = "RearVue8", pct = 78))

        showScreen()

        composeRule.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test
    fun freshDecodedFramesReadConnected() {
        // The positive half: what Connected now means is that the radar is
        // delivering targets, which is the question the rider is asking.
        BikeRadarService.radarLinkStateForUi =
            MutableStateFlow(RadarLinkState(radarGattActive = true))
        RadarStateBus.publish(
            RadarState(
                vehicles = listOf(Vehicle(id = 1, distanceM = 20, speedMs = -4f)),
                timestamp = System.currentTimeMillis(),
                source = DataSource.V2,
            ),
        )

        showScreen()

        composeRule.onNodeWithText("Connected").assertIsDisplayed()
    }
}
