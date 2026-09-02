// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import es.jjrh.bikeradar.EBikeStage
import es.jjrh.bikeradar.HaHealth
import es.jjrh.bikeradar.access.RadarGrant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the rider's audit screen says an app can do has to be what it can do.
 *
 * Hiding the overlay needs the control grant AND a live registration, and
 * registering needs read - so an app granted control alone can never hide it.
 * The consent screen says so in its own words; this row has no subtitle to
 * qualify it, and it is the screen the privacy copy sends a rider to.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRadarAccessControlOnlyTest {

    @get:Rule val composeRule = createComposeRule()

    private fun grant(read: Boolean, control: Boolean) = RadarGrant(
        packageName = "com.example.trailbuddy",
        certDigest = "aa11",
        label = "Trail Buddy",
        grantedAtMs = 0L,
        lastUsedAtMs = 0L,
        read = read,
        control = control,
    )

    private fun show(vararg grants: RadarGrant) {
        composeRule.setContent {
            UiTheme { SettingsRadarAccessContent(grants = grants.toList(), onRevoke = {}, onBack = {}) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun aControlOnlyGrantIsNotToldItCanHideTheOverlay() {
        show(grant(read = false, control = true))

        assertEquals(
            "an app with no read grant cannot hide the overlay, so the row must not say it can",
            0,
            composeRule.onAllNodesWithText("Changes your tail light and overlay", substring = true)
                .fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText("Changes your tail light", substring = true).assertIsDisplayed()
    }

    @Test
    fun theSettingsMenuRowDoesNotClaimTheOverlayForAMixedPopulation() {
        // The same claim on the earlier surface, and the one a rider meets
        // first. A mixed population is what the condition has to separate:
        // reading "2 apps can change your tail light and overlay" when only one
        // of them can is the defect this whole split was written to remove,
        // surviving at the case a per-app screen never shows.
        composeRule.setContent {
            UiTheme {
                SettingsMenuBody(
                    navController = rememberNavController(),
                    devUnlocked = false,
                    prefsSnap = SnapshotFixtures.defaultPrefsSnapshot(),
                    btEnabled = true,
                    radarLink = DeviceLinkState.LIVE,
                    dashcamLink = DeviceLinkState.NOT_PAIRED,
                    radarBattery = null,
                    dashcamBattery = null,
                    ebikeReceiving = false,
                    ebikeStage = EBikeStage.NO_BONDED_BIKE,
                    haConfigured = false,
                    haHealth = HaHealth.Unknown,
                    permissionsGrantedCount = 4,
                    permissionsRequiredMissing = 0,
                    permissionsTotal = 4,
                    radarGrants = listOf(
                        grant(read = true, control = true),
                        grant(read = false, control = true).copy(packageName = "com.example.other"),
                    ),
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(
            "one of the two cannot hide the overlay, so the summary must not say both can",
            0,
            composeRule.onAllNodesWithText("and overlay", substring = true).fetchSemanticsNodes().size,
        )
        // Counted rather than asserted displayed: the menu scrolls and this row
        // sits below the fold, so `assertIsDisplayed` would be about the
        // viewport. What matters is which of the two strings was chosen.
        assertEquals(
            "the weaker wording is what a mixed population has to get",
            1,
            composeRule.onAllNodesWithText("change your tail light", substring = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun theOrdinaryGrantStillNamesTheOverlay() {
        // The other half: with read as well the app really can take the rider's
        // display, and the row has to say so.
        show(grant(read = true, control = true))

        composeRule.onNodeWithText("Changes your tail light and overlay", substring = true).assertIsDisplayed()
    }
}
