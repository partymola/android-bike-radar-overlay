// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The pairing step counts a radar as paired by a bonded pin or a radar name
 * that is not a front camera ([es.jjrh.bikeradar.RadarSelection.setupHasRadar]),
 * offers its device pick only while no
 * radar is settled by name, keeps the pick changeable after one is made, and
 * titles the pick by whether the card above already reads PAIRED.
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingPairingRadarPickTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs by lazy { Prefs(app) }

    private val unknownRadarMac = "AA:BB:CC:DD:EE:01"
    private val radarMac = "AA:BB:CC:DD:EE:02"
    private val watchMac = "AA:BB:CC:DD:EE:03"

    /** The pick's title while the card above says the radar is not paired. */
    private val pickTitle = "Already paired your radar?"

    /** The same pick once the card above reads PAIRED. */
    private val chooseTitle = "Choose this bike's radar"

    private fun bond(vararg devices: Pair<String, String>) {
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setBondedDevices(
            devices.map { (mac, name) -> adapter.getRemoteDevice(mac).also { shadowOf(it).setName(name) } }.toSet(),
        )
    }

    private fun show() {
        composeRule.setContent {
            UiTheme { PairingStep(navController = rememberNavController(), prefs = prefs, onFinish = {}) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a radar paired under an unknown name can be picked in setup`() {
        bond(unknownRadarMac to "BackLight 9")
        show()
        composeRule.onNodeWithText(pickTitle).performScrollTo().performClick()
        composeRule.onNodeWithText("BackLight 9").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(unknownRadarMac, prefs.radarMac)
        composeRule.onAllNodesWithText("PAIRED").assertCountEquals(1)
        // The pick stays on screen, marked, so it can be changed, and now
        // reads as a choice rather than asking whether one is paired.
        composeRule.onAllNodesWithText(pickTitle).assertCountEquals(0)
        composeRule.onAllNodesWithText(chooseTitle).assertCountEquals(1)
        composeRule.onAllNodesWithText("Selected").assertCountEquals(1)
    }

    @Test
    fun `a wrong pick is undone on the same screen`() {
        bond(radarMac to "RearVue8", watchMac to "Watch 7")
        prefs.radarMac = watchMac
        show()
        composeRule.onNodeWithText(chooseTitle).performScrollTo().performClick()
        composeRule.onNodeWithText("RearVue8").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(radarMac, prefs.radarMac)
        composeRule.onAllNodesWithText(chooseTitle).assertCountEquals(0)
        composeRule.onAllNodesWithText(pickTitle).assertCountEquals(0)
    }

    @Test
    fun `a radar the app knows by name never meets the list`() {
        bond(radarMac to "RearVue8", watchMac to "Watch 7")
        show()

        composeRule.onAllNodesWithText("PAIRED").assertCountEquals(1)
        composeRule.onAllNodesWithText(pickTitle).assertCountEquals(0)
        composeRule.onAllNodesWithText(chooseTitle).assertCountEquals(0)
    }

    @Test
    fun `two named radars with none picked read as not selected`() {
        bond(radarMac to "RearVue8", "AA:BB:CC:DD:EE:08" to "RTL515")
        show()

        composeRule.onAllNodesWithText("PAIRED").assertCountEquals(1)
        composeRule.onAllNodesWithText("Not selected").assertCountEquals(1)
        composeRule.onAllNodesWithText("Rear radar").assertCountEquals(1)
        composeRule.onNodeWithText(chooseTitle).performScrollTo().performClick()
        composeRule.onNodeWithText("RTL515").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals("AA:BB:CC:DD:EE:08", prefs.radarMac)
        composeRule.onAllNodesWithText("Not selected").assertCountEquals(0)
    }

    @Test
    fun `a front camera alone is not a paired radar`() {
        // The live link would never stream from it, so the card asks for a
        // radar and the pick keeps asking whether one is paired.
        bond("AA:BB:CC:DD:EE:06" to "Varia Vue 10101", watchMac to "Watch 7")
        show()

        composeRule.onAllNodesWithText("Varia Vue 10101").assertCountEquals(0)
        composeRule.onAllNodesWithText("PAIRED").assertCountEquals(0)
        composeRule.onAllNodesWithText("Open Bluetooth settings").assertCountEquals(1)
        composeRule.onAllNodesWithText(pickTitle).assertCountEquals(1)
        composeRule.onAllNodesWithText(chooseTitle).assertCountEquals(0)
    }

    @Test
    fun `the dashcam, the eBike and front cameras are not offered as the radar`() {
        // The camera named as one is not saved yet: its card sits below this
        // one on the same step, so a rider meets the radar pick first.
        bond(
            watchMac to "Watch 7",
            "AA:BB:CC:DD:EE:04" to "Front cam",
            "AA:BB:CC:DD:EE:05" to "smart system eBike",
            "AA:BB:CC:DD:EE:06" to "Vue 10101",
        )
        prefs.dashcamMac = "AA:BB:CC:DD:EE:04"
        show()
        composeRule.onNodeWithText(pickTitle).performScrollTo().performClick()

        composeRule.onAllNodesWithText("Watch 7").assertCountEquals(1)
        composeRule.onAllNodesWithText("Front cam").assertCountEquals(0)
        composeRule.onAllNodesWithText("smart system eBike").assertCountEquals(0)
        composeRule.onAllNodesWithText("Vue 10101").assertCountEquals(0)
    }

    @Test
    fun `only the rider's own pick is marked`() {
        // "Rear light 3" reads as the radar by name, unpinned, and still gets
        // the list since it is not a rear unit for sure; the app chose it, the
        // rider did not.
        bond("AA:BB:CC:DD:EE:07" to "Rear light 3", watchMac to "Watch 7")
        show()
        composeRule.onNodeWithText(chooseTitle).performScrollTo().performClick()

        composeRule.onAllNodesWithText("Rear light 3").assertCountEquals(2)
        composeRule.onAllNodesWithText("Selected").assertCountEquals(0)
    }

    @Test
    fun `the pick says whether its list is open`() {
        bond(watchMac to "Watch 7")
        show()
        val header = composeRule.onNodeWithText(pickTitle).performScrollTo()
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        header.performClick()
        composeRule.onNodeWithText(pickTitle)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }
}
