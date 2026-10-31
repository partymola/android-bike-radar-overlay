// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBluetoothAdapter

/**
 * With Nearby devices denied, the dashcam picker and setup's pairing step ask
 * for the permission rather than reading the paired list as none paired. The
 * stock shadow answers a denied caller with its devices, which Android never
 * does, so these tests read through one that answers as Android does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [PairedListNearbyDeniedTest.BondedReadAsOnAndroid::class])
class PairedListNearbyDeniedTest {

    /** Empty while the adapter is off, whoever asks; refused without Bluetooth connect. */
    @Implements(BluetoothAdapter::class)
    class BondedReadAsOnAndroid : ShadowBluetoothAdapter() {
        @Implementation
        override fun getBondedDevices(): Set<BluetoothDevice>? {
            if (!isEnabled) return emptySet()
            val app = ApplicationProvider.getApplicationContext<Application>()
            if (app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("needs BLUETOOTH_CONNECT")
            }
            return super.getBondedDevices()
        }
    }

    private class FakeOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val adapter: BluetoothAdapter by lazy {
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val prefs by lazy { Prefs(app) }
    private lateinit var nav: NavHostController
    private val owner = FakeOwner()

    private val grantTitle = "Permissions needed"
    private val pickerGrantSub = "Grant Nearby devices to choose your dashcam"
    private val setupGrantSub = "No alerts until you grant Nearby devices"
    private val setupIntro = "Grant Nearby devices so the app can see what you've paired. Tap Permissions needed, then press back to return here."
    private val banner = "Likely matches are paired devices whose name looks like a cycling dashcam or that have advertised battery this session."
    private val pairInSettings = "Pair a new device in Android Settings"
    private val openBluetooth = "Open Bluetooth settings"
    private val pairLater = "You can pair the radar later from Bluetooth settings."
    private val notPairedYet = "Not paired yet. The pairing screen is in Android's Bluetooth settings."
    private val none = "None - I don't have one"

    @Before
    fun setUp() {
        shadowOf(adapter).setEnabled(true)
    }

    private fun grantNearby() = shadowOf(app).grantPermissions(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private fun bondCamera() {
        val camera = adapter.getRemoteDevice("AA:BB:CC:DD:EE:22")
        shadowOf(camera).setName("VUE-12345")
        shadowOf(adapter).setBondedDevices(setOf(camera))
    }

    private fun bondRadar() {
        val radar = adapter.getRemoteDevice("AA:BB:CC:DD:EE:11")
        shadowOf(radar).setName("RearVue8")
        shadowOf(adapter).setBondedDevices(setOf(radar))
    }

    private fun show(screen: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                nav = rememberNavController()
                NavHost(navController = nav, startDestination = "screen") {
                    composable("screen") { screen() }
                    composable("settings/permissions") {}
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun showPicker() = show { DashcamPickerSheet(navController = nav, prefs = prefs, fromOnboarding = false) }

    private fun showSetup() = show { UiTheme { PairingStep(navController = nav, prefs = prefs, onFinish = {}) } }

    private fun count(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun assertTheCardOpensPermissions(sub: String) {
        composeRule.onNodeWithText(grantTitle).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(sub).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("settings/permissions", nav.currentDestination?.route)
    }

    private fun assertThePickerAsksForThePermission() {
        composeRule.onAllNodesWithText(pairInSettings).assertCountEquals(0)
        composeRule.onAllNodesWithText(banner).assertCountEquals(0)
        assertEquals("the rider can still say they have no camera", 1, count(none))
        assertTheCardOpensPermissions(pickerGrantSub)
    }

    private fun assertThePickerOffersPairing() {
        composeRule.onAllNodesWithText(grantTitle).assertCountEquals(0)
        assertEquals(1, count(banner))
        assertEquals(1, count(pairInSettings))
    }

    private fun assertSetupAsksForThePermission() {
        composeRule.onAllNodesWithText(openBluetooth).assertCountEquals(0)
        composeRule.onAllNodesWithText(notPairedYet).assertCountEquals(0)
        composeRule.onAllNodesWithText(pairLater).assertCountEquals(0)
        composeRule.onAllNodesWithText("Put the radar in pair mode", substring = true).assertCountEquals(0)
        assertEquals(1, count(setupIntro))
        assertTheCardOpensPermissions(setupGrantSub)
    }

    private fun assertSetupOffersPairing() {
        composeRule.onAllNodesWithText(grantTitle).assertCountEquals(0)
        composeRule.onAllNodesWithText(setupIntro).assertCountEquals(0)
        assertEquals(1, count(openBluetooth))
        assertEquals(1, count(pairLater))
    }

    private fun advancePastThePoll() {
        composeRule.mainClock.advanceTimeBy(2_100)
        composeRule.waitForIdle()
    }

    private fun leaveGrantAndComeBack(whileAway: () -> Unit) {
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        whileAway()
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private fun grantInPermissionsAndPopBack() {
        grantNearby()
        composeRule.runOnIdle { nav.popBackStack() }
        composeRule.waitForIdle()
        assertEquals("screen", nav.currentDestination?.route)
    }

    @Test
    fun thePickerAsksWhenTheReadIsEmptyWithBluetoothOff() {
        bondCamera()
        shadowOf(adapter).setEnabled(false)
        assertTrue(adapter.bondedDevices.isEmpty())
        showPicker()
        assertThePickerAsksForThePermission()
    }

    @Test
    fun thePickerAsksWhenTheReadIsRefused() {
        bondCamera()
        assertThrows(SecurityException::class.java) { adapter.bondedDevices }
        showPicker()
        assertThePickerAsksForThePermission()
    }

    @Test
    fun thePickerOffersPairingWhenNothingIsPaired() {
        grantNearby()
        showPicker()
        assertThePickerOffersPairing()
    }

    @Test
    fun thePickerReadsBluetoothOffAsNonePaired() {
        grantNearby()
        bondCamera()
        shadowOf(adapter).setEnabled(false)
        showPicker()
        composeRule.onAllNodesWithText("VUE-12345").assertCountEquals(0)
        assertThePickerOffersPairing()
    }

    @Test
    fun aListThatCanBeReadIsShownWithScanAloneDenied() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        bondCamera()
        showPicker()
        composeRule.onAllNodesWithText(grantTitle).assertCountEquals(0)
        assertEquals(1, count("VUE-12345"))
    }

    @Test
    fun scanAloneDeniedWithNothingPairedStillAsks() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        showPicker()
        assertThePickerAsksForThePermission()
    }

    @Test
    fun thePickerListsThePairedDevicesOnceGrantedWhileOpen() {
        showPicker()
        assertEquals(1, count(grantTitle))

        grantNearby()
        bondCamera()
        advancePastThePoll()

        composeRule.onAllNodesWithText(grantTitle).assertCountEquals(0)
        assertEquals(1, count("VUE-12345"))
    }

    @Test
    fun thePickerOffersPairingOnceGrantedWhileOpenWithNothingPaired() {
        // The list reads empty before and after, so only the permission moves it.
        showPicker()
        assertEquals(1, count(grantTitle))

        grantNearby()
        advancePastThePoll()

        assertThePickerOffersPairing()
    }

    @Test
    fun thePickerReadsTheListTheMomentItIsBackInFront() {
        composeRule.mainClock.autoAdvance = false
        showPicker()
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(1, count(grantTitle))

        leaveGrantAndComeBack {
            grantNearby()
            bondCamera()
        }

        composeRule.onAllNodesWithText(grantTitle).assertCountEquals(0)
        assertEquals(1, count("VUE-12345"))
    }

    @Test
    fun thePickerListsTheCameraAfterTheCardLedToAGrantAndBack() {
        bondCamera()
        showPicker()
        assertTheCardOpensPermissions(pickerGrantSub)

        grantInPermissionsAndPopBack()

        composeRule.onAllNodesWithText(grantTitle).assertCountEquals(0)
        assertEquals(1, count("VUE-12345"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun thePairRowIsAButtonAtTouchHeight() {
        grantNearby()
        showPicker()

        composeRule.onNodeWithText(pairInSettings)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val height = composeRule.onNodeWithText(pairInSettings).fetchSemanticsNode().size.height
        assertTrue("$height px", height >= 48 * composeRule.density.density)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun thePickerRowsAreRadiosAtTouchHeight() {
        grantNearby()
        bondCamera()
        showPicker()
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

        composeRule.onNodeWithText(none).assert(radio).assertIsSelected()
        composeRule.onNodeWithText("VUE-12345").assert(radio).assertIsNotSelected()
        val height = composeRule.onNodeWithText(none).fetchSemanticsNode().size.height
        assertTrue("$height px", height >= 48 * composeRule.density.density)

        composeRule.onNodeWithText("VUE-12345").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("VUE-12345").assertIsSelected()
        composeRule.onNodeWithText(none).assertIsNotSelected()
    }

    @Test
    fun setupAsksWhenTheReadIsEmptyWithBluetoothOff() {
        bondRadar()
        shadowOf(adapter).setEnabled(false)
        assertTrue(adapter.bondedDevices.isEmpty())
        showSetup()
        assertSetupAsksForThePermission()
    }

    @Test
    fun setupAsksWhenTheReadIsRefused() {
        bondRadar()
        assertThrows(SecurityException::class.java) { adapter.bondedDevices }
        showSetup()
        assertSetupAsksForThePermission()
    }

    @Test
    fun setupOffersPairingWhenNothingIsPaired() {
        grantNearby()
        showSetup()
        assertSetupOffersPairing()
    }

    @Test
    fun setupReadsBluetoothOffAsNonePaired() {
        grantNearby()
        bondRadar()
        shadowOf(adapter).setEnabled(false)
        showSetup()
        composeRule.onAllNodesWithText("PAIRED").assertCountEquals(0)
        assertSetupOffersPairing()
    }

    @Test
    fun setupOffersPairingOnceGrantedWhileOpenWithNothingPaired() {
        // The list reads empty before and after, so only the permission moves it.
        showSetup()
        assertEquals(1, count(grantTitle))

        grantNearby()
        advancePastThePoll()

        assertSetupOffersPairing()
    }

    @Test
    fun setupReadsTheListTheMomentItIsBackInFront() {
        composeRule.mainClock.autoAdvance = false
        showSetup()
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(1, count(grantTitle))

        leaveGrantAndComeBack {
            grantNearby()
            bondRadar()
        }

        composeRule.onAllNodesWithText(grantTitle).assertCountEquals(0)
        assertEquals(1, count("PAIRED"))
    }

    @Test
    fun setupReadsTheRadarAfterTheCardLedToAGrantAndBack() {
        bondRadar()
        showSetup()
        assertTheCardOpensPermissions(setupGrantSub)

        grantInPermissionsAndPopBack()

        composeRule.onAllNodesWithText(grantTitle).assertCountEquals(0)
        assertEquals(1, count("PAIRED"))
    }
}
