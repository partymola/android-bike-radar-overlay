// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import es.jjrh.bikeradar.HaStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tapping a System row must reach the callback with that row's own target.
 *
 * The goldens cannot cover this and it is worth saying why: the chevron is
 * drawn from `SystemRow.target`, not from the click handler, so a row whose
 * `clickable` was removed renders IDENTICALLY to a working one. Every pixel
 * test passes over a card whose taps do nothing. This is what fails instead,
 * on any of: a removed `clickable`, a handler that ignores its argument, or
 * two rows' targets swapped.
 *
 * [SystemRowRouteTest] owns the other half: that each target names a screen
 * the navigation graph actually registers.
 */
@RunWith(RobolectricTestRunner::class)
class SystemRowTapTest {

    @get:Rule val composeRule = createComposeRule()

    private val tapped = mutableListOf<SystemRowTarget>()

    private fun card() {
        composeRule.setContent {
            UiTheme {
                SystemCard(
                    radarFresh = true,
                    hasBond = true,
                    btEnabled = true,
                    dashcamOwned = true,
                    dashcamFresh = true,
                    dashcamPaired = true,
                    dashcamDisplayName = "Front cam",
                    radarBattery = null,
                    dashcamBattery = null,
                    haStatus = HaStatus.READY,
                    ebikeEnabled = true,
                    ebikeReceiving = true,
                    onRowClick = { tapped += it },
                )
            }
        }
    }

    @Test
    fun `each row reports its own target`() {
        card()
        // By label, because that is what a rider aims at. Tapped in a
        // different order from the rendered one so a handler that ignored its
        // argument and replayed the card's order would still fail.
        composeRule.onNodeWithText("Home Assistant").performClick()
        composeRule.onNodeWithText("Rear radar").performClick()
        composeRule.onNodeWithText("eBike").performClick()
        composeRule.onNodeWithText("Front dashcam").performClick()

        assertEquals(
            listOf(
                SystemRowTarget.HA,
                SystemRowTarget.RADAR,
                SystemRowTarget.EBIKE,
                SystemRowTarget.DASHCAM,
            ),
            tapped,
        )
    }

    @Test
    fun `a row reports once per tap`() {
        // The row nests a Column and a Row inside the clickable; if the
        // modifier were applied at more than one level a single tap would
        // deliver twice, which on a navigating row means two screens pushed.
        card()
        composeRule.onNodeWithText("Rear radar").performClick()
        assertEquals(listOf(SystemRowTarget.RADAR), tapped)
    }
}
