// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.data.EBikeOwnership
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the [SettingsEBikeContent] leaf. Covers the
 * headline states of the Settings → eBike screen:
 *
 *  - noOwnership: ownership = NO. Toggle row replaced by the promotion
 *    IntentCard; Status and Actions hidden.
 *  - yesToggleOff: ownership = YES, eBikeDataEnabled = false. Toggle subtitle
 *    invites turning it on; Status and Actions hidden.
 *  - yesWaiting: ownership = YES, enabled, receiving = false. Status reads
 *    "Waiting for Bosch Flow"; Actions shows "Open Bosch Flow".
 *  - yesReceiving: ownership = YES, enabled, receiving = true. Status reads
 *    "Receiving live data".
 *
 * Renders via Robolectric Native Graphics. Verify with
 * `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
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
                    eBikeDataEnabled = false,
                    receiving = false,
                    onOwnershipYes = {},
                    onToggleEBikeData = {},
                    forgotToLockEnabled = true,
                    onToggleForgotToLock = {},
                    onOpenFlow = {},
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
                    eBikeDataEnabled = false,
                    receiving = false,
                    onOwnershipYes = {},
                    onToggleEBikeData = {},
                    forgotToLockEnabled = true,
                    onToggleForgotToLock = {},
                    onOpenFlow = {},
                )
            }
        }
    }

    @Test
    fun yesWaiting() {
        captureRoboImage {
            UiTheme {
                SettingsEBikeContent(
                    navController = rememberNavController(),
                    ownership = EBikeOwnership.YES,
                    eBikeDataEnabled = true,
                    receiving = false,
                    onOwnershipYes = {},
                    onToggleEBikeData = {},
                    forgotToLockEnabled = true,
                    onToggleForgotToLock = {},
                    onOpenFlow = {},
                )
            }
        }
    }

    @Test
    fun yesReceiving() {
        captureRoboImage {
            UiTheme {
                SettingsEBikeContent(
                    navController = rememberNavController(),
                    ownership = EBikeOwnership.YES,
                    eBikeDataEnabled = true,
                    receiving = true,
                    onOwnershipYes = {},
                    onToggleEBikeData = {},
                    forgotToLockEnabled = true,
                    onToggleForgotToLock = {},
                    onOpenFlow = {},
                )
            }
        }
    }
}
