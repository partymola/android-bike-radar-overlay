// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.EBikeStage
import es.jjrh.bikeradar.HaHealth
import es.jjrh.bikeradar.data.PrefsSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the Settings row actually says about the experimental toggles.
 *
 * `ExperimentalFeaturesCountTest` pins the pure count and
 * `ExperimentalFeaturesSeamTest` pins the list against the screen, but neither
 * reaches the row. Every golden renders this screen with both toggles OFF, so
 * replacing the live count with a literal zero keeps the whole suite green and
 * every golden byte-identical, and a rider with a toggle on reads that nothing
 * is on. That is the same defect this row was changed to remove.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsExperimentalRowTest {

    @get:Rule val composeRule = createComposeRule()

    private fun showMenu(snap: PrefsSnapshot) {
        composeRule.setContent {
            UiTheme {
                SettingsMenuBody(
                    navController = rememberNavController(),
                    devUnlocked = true,
                    prefsSnap = snap,
                    btEnabled = true,
                    radarLink = DeviceLinkState.LIVE,
                    dashcamLink = DeviceLinkState.NOT_PAIRED,
                    radarBattery = BatteryEntry("radar", "RearVue8", 78, readAtMs = 1_000L),
                    dashcamBattery = null,
                    ebikeReceiving = false,
                    ebikeStage = EBikeStage.NOT_STARTED,
                    haConfigured = false,
                    haHealth = HaHealth.Unknown,
                    permissionsGrantedCount = 3,
                    permissionsRequiredMissing = 0,
                    permissionsTotal = 3,
                    radarGrants = emptyList(),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun snapshot(precog: Boolean, dropFallback: Boolean) = SnapshotFixtures.defaultPrefsSnapshot()
        .copy(precogEnabled = precog, radarDropTrackFallbackEnabled = dropFallback)

    @Test
    fun oneToggleOnIsCountedOnTheRow() {
        showMenu(snapshot(precog = true, dropFallback = false))

        composeRule.onNodeWithText("On (1 of 2)").assertExists()
    }

    @Test
    fun theOtherToggleOnIsCountedToo() {
        // Separately from the first: a row wired to one specific flag rather
        // than to the count gets one of these right and the other wrong.
        showMenu(snapshot(precog = false, dropFallback = true))

        composeRule.onNodeWithText("On (1 of 2)").assertExists()
    }

    @Test
    fun bothOnIsCounted() {
        showMenu(snapshot(precog = true, dropFallback = true))

        composeRule.onNodeWithText("On (2 of 2)").assertExists()
    }

    @Test
    fun nothingOnKeepsItsOwnWording() {
        showMenu(snapshot(precog = false, dropFallback = false))

        composeRule.onNodeWithText("All off").assertExists()
    }
}
