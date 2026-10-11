// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
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

/** Settings → Radar with a pin made through "My radar isn't listed": a wrong
 *  one can be undone, and a right one is shown as the radar it is. */
@RunWith(RobolectricTestRunner::class)
class SettingsRadarWrongPinTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Section labels render in capitals. */
    private val chooserLabel = "CHOOSE THIS BIKE'S RADAR"

    private fun bond(vararg devices: Pair<String, String>) {
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setBondedDevices(
            devices.map { (mac, name) -> adapter.getRemoteDevice(mac).also { shadowOf(it).setName(name) } }.toSet(),
        )
    }

    private fun show(prefs: Prefs) {
        composeRule.setContent { SettingsRadarDevice(navController = rememberNavController(), prefs = prefs) }
        composeRule.waitForIdle()
    }

    @Test
    fun `the one real radar is offered back over a wrong pin`() {
        bond("AA:BB:CC:DD:EE:11" to "RearVue8", "AA:BB:CC:DD:EE:22" to "Watch 7")
        val prefs = Prefs(app).apply { radarMac = "AA:BB:CC:DD:EE:22" }
        show(prefs)
        // Present first, so the absence checks below cannot pass on a label
        // that never matched.
        composeRule.onAllNodesWithText(chooserLabel).assertCountEquals(1)

        composeRule.onNodeWithText("RearVue8").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals("AA:BB:CC:DD:EE:11", prefs.radarMac)
        composeRule.onAllNodesWithText(chooserLabel).assertCountEquals(0)
    }

    @Test
    fun `a pinned radar with an unknown name gets its device card`() {
        bond("AA:BB:CC:DD:EE:33" to "BackLight 9")
        show(Prefs(app).apply { radarMac = "AA:BB:CC:DD:EE:33" })

        composeRule.onAllNodesWithText("Pair your radar").assertCountEquals(0)
        composeRule.onAllNodesWithText("BackLight 9").assertCountEquals(1)
    }

    @Test
    fun `the dashcam and the eBike are not offered as the radar`() {
        bond(
            "AA:BB:CC:DD:EE:11" to "RearVue8",
            "AA:BB:CC:DD:EE:22" to "Watch 7",
            "AA:BB:CC:DD:EE:04" to "Front cam",
            "AA:BB:CC:DD:EE:05" to "smart system eBike",
        )
        show(Prefs(app).apply { dashcamMac = "AA:BB:CC:DD:EE:04" })
        composeRule.onNodeWithText("My radar isn't listed").performScrollTo().performClick()

        composeRule.onAllNodesWithText("Watch 7").assertCountEquals(1)
        composeRule.onAllNodesWithText("Front cam").assertCountEquals(0)
        composeRule.onAllNodesWithText("smart system eBike").assertCountEquals(0)
    }

    @Test
    fun `a front camera is not offered over a right pin`() {
        bond("AA:BB:CC:DD:EE:33" to "BackLight 9", "AA:BB:CC:DD:EE:44" to "Varia Vue 10101")
        show(Prefs(app).apply { radarMac = "AA:BB:CC:DD:EE:33" })
        composeRule.onNodeWithText("My radar isn't listed").performScrollTo().performClick()

        composeRule.onAllNodesWithText(chooserLabel).assertCountEquals(0)
        composeRule.onAllNodesWithText("Varia Vue 10101").assertCountEquals(0)
    }

    @Test
    fun `two radars named only by rear can be chosen between`() {
        bond("AA:BB:CC:DD:EE:55" to "Rear light 3", "AA:BB:CC:DD:EE:66" to "Rear unit 5")
        val prefs = Prefs(app)
        show(prefs)
        composeRule.onAllNodesWithText(chooserLabel).assertCountEquals(1)

        composeRule.onNodeWithText("Rear unit 5").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals("AA:BB:CC:DD:EE:66", prefs.radarMac)
    }

    @Test
    fun `a front camera beside the radar leaves it named`() {
        bond("AA:BB:CC:DD:EE:11" to "RearVue8", "AA:BB:CC:DD:EE:44" to "Varia Vue 10101")
        show(Prefs(app))

        composeRule.onAllNodesWithText("Not selected").assertCountEquals(0)
        composeRule.onAllNodesWithText("RearVue8").assertCountEquals(1)
    }
}
