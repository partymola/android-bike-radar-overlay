// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.EBikeStage
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.RadarSelection
import es.jjrh.bikeradar.access.RadarGrant
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.EBikeOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * TalkBack reads a click label inside its own "double-tap to %s", so a label
 * is a lowercase infinitive saying what the tap does, never the button's
 * visible text. [BrOutlinedButton] carries none and leaves the action to its
 * name; [SettingsActionRow] always names its action, even where its icon's
 * description already says it.
 */
@RunWith(RobolectricTestRunner::class)
class ClickLabelTest {

    @get:Rule val composeRule = createComposeRule()

    private fun clickLabel(matcher: SemanticsMatcher): String? = composeRule.onNode(matcher and hasClickAction())
        .fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label

    @Test
    fun anOutlinedButtonLeavesTheActionToItsName() {
        composeRule.setContent { UiTheme { BrOutlinedButton(label = "Stop scanning", onClick = {}) } }
        assertEquals(null, clickLabel(hasText("Stop scanning")))
    }

    @Test
    fun theRadarScreensOpenSaysItOpensBluetoothSettings() {
        composeRule.setContent {
            UiTheme {
                SettingsRadarDeviceContent(
                    onBack = {},
                    bonded = listOf(RadarSelection.BondedRadar("AA:AA:AA:AA:AA:AA", "RearVue8")),
                    chosenMac = null,
                    activeName = "RearVue8",
                    link = DeviceLinkState.LIVE,
                    batteryPct = null,
                    offerChooser = false,
                )
            }
        }
        assertEquals("open Bluetooth settings", clickLabel(hasText("Open")))
    }

    @Test
    fun theEBikeScreensOpenSaysItOpensFlow() {
        composeRule.setContent {
            UiTheme {
                SettingsEBikeContent(
                    navController = rememberNavController(),
                    ownership = EBikeOwnership.YES,
                    eBikeDataEnabled = true,
                    receiving = true,
                    stage = EBikeStage.RECEIVING,
                    forgotToLockEnabled = false,
                    canBypassDnd = false,
                    onOwnershipYes = {},
                    onToggleEBikeData = {},
                    onToggleForgotToLock = {},
                    onOverrideDndClick = {},
                    onOpenFlow = {},
                )
            }
        }
        assertEquals("open Bosch Flow", clickLabel(hasText("Open")))
    }

    @Test
    fun clearingTheSavedDashcamSaysWhatItClears() {
        composeRule.setContent {
            UiTheme {
                SettingsDashcamContent(
                    navController = rememberNavController(),
                    ownership = DashcamOwnership.NO,
                    dashcamMac = "AA:BB:CC:DD:EE:22",
                    dashcamDisplayName = "Front cam",
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
                    onPickDeviceClick = {},
                    onWarnWhenOffChange = {},
                    onWalkAwayEnabledChange = {},
                    onWalkAwayThresholdChange = {},
                    onWalkAwayThresholdFinished = {},
                    onOverrideDndClick = {},
                )
            }
        }
        assertEquals("clear the saved dashcam", clickLabel(hasText("Clear")))
    }

    @Test
    fun anAllowedAppsBinSaysItStopsSharing() {
        composeRule.setContent {
            UiTheme {
                SettingsRadarAccessContent(
                    grants = listOf(RadarGrant("com.example.trailbuddy", "aa11", "Trail Buddy", 0L, 0L, read = true, control = false)),
                    onRevoke = {},
                    onBack = {},
                )
            }
        }
        assertEquals("stop sharing", clickLabel(hasContentDescription("Stop sharing")))
    }

    @Test
    fun theNotListedRowSaysWhetherItShowsOrHidesTheDevices() {
        composeRule.setContent {
            var expanded by remember { mutableStateOf(false) }
            UiTheme {
                RadarNotListedHatch(
                    others = listOf(RadarSelection.BondedRadar("CC:CC:CC:CC:CC:CC", "Pixel Watch")),
                    chosenMac = null,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    onSelectRadar = {},
                )
            }
        }
        val row = hasText("My radar isn't listed")
        assertEquals("show paired devices", clickLabel(row))
        composeRule.onNode(row and hasClickAction()).performClick()
        composeRule.waitForIdle()
        assertEquals("hide paired devices", clickLabel(row))
    }

    @Test
    fun aLicenceRowSaysItOpensTheLicence() {
        composeRule.setContent { SettingsLicenses(navController = rememberNavController()) }
        assertEquals("open the licence", clickLabel(hasText("Kotlin")))
    }

    /** Resolved through the resource system, as TalkBack gets them, and found
     *  by name prefix, so a new `a11y_action_` label is checked as soon as it
     *  exists; the count fails until it is raised to include it. */
    @Test
    fun everyClickLabelStartsLowercaseInEveryLocale() {
        val app: Context = ApplicationProvider.getApplicationContext()
        val names = R.string::class.java.fields.map { it.name }.filter { it.startsWith("a11y_action_") } +
            listOf("settings_radardev_show_devices", "settings_radardev_hide_devices", "settings_licenses_open_action")
        assertEquals(9, names.size)
        val firstLabel = mutableSetOf<String>()
        for (tag in listOf("en", "es", "pl")) {
            val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val res = app.createConfigurationContext(config).resources
            for (name in names) {
                val text = res.getString(R.string::class.java.getField(name).getInt(null))
                assertTrue("$tag $name is \"$text\"", text.first().isLowerCase())
            }
            firstLabel += res.getString(R.string.a11y_action_change)
        }
        assertEquals("each locale resolved its own text", 3, firstLabel.size)
    }
}
