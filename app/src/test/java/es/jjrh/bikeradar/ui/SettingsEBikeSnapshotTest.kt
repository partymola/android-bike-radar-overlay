// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.LdiOutcome
import es.jjrh.bikeradar.data.EBikeOwnership
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the [SettingsEBikeContent] leaf. Covers the
 * four headline states from the design's §1 / §5:
 *
 *  - noOwnership: ownership = NO. Toggle row replaced by the promotion
 *    IntentCard. The status group and ACTIONS group are hidden.
 *  - yesToggleOff: ownership = YES, ldiEnabled = false. Status reads
 *    Off, ACTIONS hidden, toggle subtitle reads `Turn on to set up`.
 *  - yesToggleOnNoBond: ownership = YES, ldiEnabled = true,
 *    bondedAddress = null. ACTIONS shows `Open Flow to pair`.
 *  - yesToggleOnBonded: ownership = YES, ldiEnabled = true,
 *    bondedAddress non-null. Status reads `Paired with bike at ...`,
 *    ACTIONS shows `Unpair this bike`.
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsEBikeSnapshotTest {

    @Test
    fun noOwnership() {
        captureRoboImage {
            UiTheme {
                SettingsEBikeContent(
                    navController = rememberNavController(),
                    ownership = EBikeOwnership.NO,
                    ldiEnabled = false,
                    bondedAddress = null,
                    outcome = LdiOutcome.Idle,
                    onOwnershipYes = {},
                    onToggleLdi = {},
                    onOpenFlow = {},
                    onUnpair = {},
                )
            }
        }
    }

    @Test
    fun yesToggleOff() {
        captureRoboImage {
            UiTheme {
                SettingsEBikeContent(
                    navController = rememberNavController(),
                    ownership = EBikeOwnership.YES,
                    ldiEnabled = false,
                    bondedAddress = null,
                    outcome = LdiOutcome.Idle,
                    onOwnershipYes = {},
                    onToggleLdi = {},
                    onOpenFlow = {},
                    onUnpair = {},
                )
            }
        }
    }

    @Test
    fun yesToggleOnNoBond() {
        captureRoboImage {
            UiTheme {
                SettingsEBikeContent(
                    navController = rememberNavController(),
                    ownership = EBikeOwnership.YES,
                    ldiEnabled = true,
                    bondedAddress = null,
                    outcome = LdiOutcome.Advertising,
                    onOwnershipYes = {},
                    onToggleLdi = {},
                    onOpenFlow = {},
                    onUnpair = {},
                )
            }
        }
    }

    @Test
    fun yesToggleOnBonded() {
        captureRoboImage {
            UiTheme {
                SettingsEBikeContent(
                    navController = rememberNavController(),
                    ownership = EBikeOwnership.YES,
                    ldiEnabled = true,
                    bondedAddress = "AA:BB:CC:DD:EE:FF",
                    outcome = LdiOutcome.Paired("AA:BB:CC:DD:EE:FF"),
                    onOwnershipYes = {},
                    onToggleLdi = {},
                    onOpenFlow = {},
                    onUnpair = {},
                )
            }
        }
    }
}
