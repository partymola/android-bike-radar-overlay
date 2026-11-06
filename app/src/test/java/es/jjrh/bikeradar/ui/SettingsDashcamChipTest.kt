// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.data.DashcamOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The camera card's Pick and Change chip is a button TalkBack names as one and
 * says what it opens, and its touch target is 48 dp each way though it is
 * drawn smaller.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsDashcamChipTest {

    @get:Rule val composeRule = createComposeRule()

    private fun showAndTap(dashcamMac: String?, label: String): Int {
        var taps = 0
        composeRule.setContent {
            UiTheme {
                SettingsDashcamContent(
                    navController = rememberNavController(),
                    ownership = DashcamOwnership.YES,
                    dashcamMac = dashcamMac,
                    dashcamDisplayName = dashcamMac?.let { "Front cam" },
                    dashcamWarnWhenOff = false,
                    dashcamConnected = false,
                    bluetoothOn = true,
                    missing = null,
                    onGrantPermissions = {},
                    onOpenBluetoothSettings = {},
                    dashcamBatteryPct = null,
                    walkAwayAlarmEnabled = false,
                    walkAwayThreshold = 30,
                    canBypassDnd = false,
                    onOwnershipChange = {},
                    onClearSavedClick = {},
                    onPickDeviceClick = { taps++ },
                    onWarnWhenOffChange = {},
                    onWalkAwayEnabledChange = {},
                    onWalkAwayThresholdChange = {},
                    onWalkAwayThresholdFinished = {},
                    onOverrideDndClick = {},
                )
            }
        }
        val chip = composeRule.onNodeWithText(label)
        chip.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val node = chip.fetchSemanticsNode()
        assertEquals("Select dashcam", node.config.getOrNull(SemanticsActions.OnClick)?.label)
        // The chip is drawn under 48 dp and relies on Compose extending a
        // clickable's touch target to 48 dp: the reported bounds, and a real
        // tap landing 5 dp above the drawn edge.
        val touch = node.touchBoundsInRoot
        with(composeRule.density) {
            assertTrue("touch height ${touch.height.toDp()}", touch.height.toDp() >= 48.dp)
            assertTrue("touch width ${touch.width.toDp()}", touch.width.toDp() >= 48.dp)
        }
        chip.performClick()
        chip.performTouchInput { click(Offset(centerX, -5.dp.toPx())) }
        return taps
    }

    @Test
    fun changeIsAButtonWithAFullSizeTarget() {
        assertEquals(2, showAndTap(dashcamMac = "AA:BB:CC:DD:EE:22", label = "Change"))
    }

    @Test
    fun pickIsAButtonWithAFullSizeTarget() {
        assertEquals(2, showAndTap(dashcamMac = null, label = "Pick"))
    }
}
