// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import es.jjrh.bikeradar.LdiOutcome
import es.jjrh.bikeradar.data.EBikeOwnership
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi goldens for the [SettingsEBikeContent] leaf. Covers the
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
 * CI does not run these - Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class SettingsEBikeSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    @Test
    fun noOwnership() {
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
