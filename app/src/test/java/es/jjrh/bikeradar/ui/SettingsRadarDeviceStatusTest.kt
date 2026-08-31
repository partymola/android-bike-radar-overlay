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
 * "Connecting…", never the blanket "No signal", and with the service down
 * "No signal" is what renders, because then it is true.
 *
 * The words are the shared device vocabulary, so these literals are also
 * what the home System row says about the same radar.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRadarDeviceStatusTest {

    @get:Rule val composeRule = createComposeRule()

    /** The disclosure, spelled out rather than read from the resource: a test
     *  asserting a string against itself stays green when the copy is gutted. */
    private val limitedSourceNote =
        "This radar reports distance only. You get approach beeps and the " +
            "all-clear, and the overlay colours show distance rather than " +
            "speed. No urgent warning, no close-pass logging, and no " +
            "drop-alert sound unless your eBike is connected."

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
    fun twoBondedRadarsAndNoPinStillReadsTheLiveState() {
        // Every other test here bonds exactly ONE radar, which makes "is there
        // a radar" and "which radar" the same question and lets a wiring that
        // asks the wrong one pass. They are not the same: with two bonded and
        // none pinned, RadarSelection.shouldLinkRadar falls back to name-match
        // and streams from one, while no name can be resolved. A card asking
        // "which" reads "Not paired" over that live stream, and the rider is
        // sent to re-pair a radar that is beeping at traffic.
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val second = adapter.getRemoteDevice("AA:BB:CC:DD:EE:22")
        shadowOf(second).setName("RearVue8 spare")
        val first = adapter.getRemoteDevice("AA:BB:CC:DD:EE:11")
        shadowOf(adapter).setBondedDevices(setOf(first, second))

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

        composeRule.onNodeWithText("Live").assertIsDisplayed()
        composeRule.onNodeWithText("Not paired").assertDoesNotExist()
    }

    @Test
    fun liveLinkWithoutDataYetReadsConnecting() {
        BikeRadarService.radarLinkStateForUi =
            MutableStateFlow(RadarLinkState(radarGattActive = true))

        showScreen()

        composeRule.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test
    fun serviceDownReadsNoSignal() {
        BikeRadarService.radarLinkStateForUi = null

        showScreen()

        composeRule.onNodeWithText("No signal").assertIsDisplayed()
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
    fun aDropOlderThanTheBridgeReadsNoSignal() {
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

        composeRule.onNodeWithText("No signal").assertIsDisplayed()
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
        composeRule.onNodeWithText("No signal").assertIsDisplayed()

        BikeRadarService.radarLinkStateForUi =
            MutableStateFlow(RadarLinkState(radarGattActive = true))
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test
    fun aFreshBatteryAloneDoesNotReadConnected() {
        // A battery reading is not evidence that data is flowing. The setup
        // sequence publishes one on every attempt that reaches its battery
        // step, so a radar aborting and retrying every 1.5 s keeps a reading
        // permanently fresh while sending no targets at all. Scoring
        // Connected off the battery would call that radar Connected, which is
        // a worse lie than either state the tri-state exists to choose
        // between.
        BikeRadarService.radarLinkStateForUi =
            MutableStateFlow(RadarLinkState(radarGattActive = true))
        BatteryStateBus.update(BatteryEntry(slug = "rearvue8", name = "RearVue8", pct = 78))

        showScreen()

        composeRule.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test
    fun aRangeOnlyRadarSaysWhatItCannotDo() {
        // A rider whose radar streams range and nothing else gets a working
        // overlay and working beeps, and silently loses speed colours,
        // close-pass logging and the dropout warning. Without this line the
        // card reads Connected and the missing half looks like a bug in the
        // app rather than the limit of the hardware.
        BikeRadarService.radarLinkStateForUi =
            MutableStateFlow(RadarLinkState(radarGattActive = true))
        RadarStateBus.publish(
            RadarState(
                vehicles = listOf(Vehicle(id = 1, distanceM = 20, speedMs = 0f, lateralUnknown = true)),
                timestamp = System.currentTimeMillis(),
                source = DataSource.V1,
            ),
        )

        showScreen()

        // "Limited", not "Live": green is the app's "you are covered" signal
        // and this link cannot raise the urgent cue. Same word the home row
        // uses for the same radar.
        composeRule.onNodeWithText("Limited").assertIsDisplayed()
        composeRule
            .onNodeWithText(limitedSourceNote)
            .assertIsDisplayed()
    }

    @Test
    fun aFullRadarDoesNotCarryTheRangeOnlyNote() {
        // The other half of the claim: the note is about the source, not
        // decoration on every connected card.
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

        composeRule
            .onNodeWithText(limitedSourceNote)
            .assertDoesNotExist()
    }

    @Test
    fun freshDecodedFramesReadLive() {
        // The positive half: what "Live" means is that the radar is
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
        BatteryStateBus.update(BatteryEntry(slug = "rearvue8", name = "RearVue8", pct = 78))

        showScreen()

        composeRule.onNodeWithText("Live").assertIsDisplayed()
        // The chip's wiring from the battery bus through this screen is pinned
        // nowhere else - the goldens render the stateless leaf with a literal
        // percentage passed in, so dropping the body's lookup leaves every
        // other test green and the rider with a blank chip. It is asserted in
        // THIS test because the chip only renders while Connected.
        composeRule.onNodeWithText("78%").assertIsDisplayed()
    }
}
