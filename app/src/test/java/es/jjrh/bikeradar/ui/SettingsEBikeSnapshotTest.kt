// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.EBikeStage
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
 *    "Waiting for Flow"; Actions shows "Open Bosch Flow".
 *  - yesReceiving: ownership = YES, enabled, receiving = true. Status reads
 *    "Live". The forgot-to-lock Override-DND row shows its "tap to allow"
 *    state (canBypassDnd = false).
 *  - forgotLockDndAllowed: as yesReceiving but canBypassDnd = true, pinning the
 *    Override-DND row's "allowed" subtitle.
 *  - yesNotPermitted / yesNoBondedBike: the two ways the link fails before
 *    Flow is involved. They get their own goldens because the advice differs
 *    and is the whole point of distinguishing them: telling a rider to open
 *    Flow about a missing Bluetooth grant sends them where nothing can help.
 *
 * `stage` is always consistent with `receiving` here. A fixture pairing
 * `receiving = false` with `EBikeStage.RECEIVING` renders correctly through
 * the else branch and is still a trap for the next editor, because it asserts
 * a state the app cannot be in.
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
                    stage = EBikeStage.NOT_STARTED,
                    onOwnershipYes = {},
                    onToggleEBikeData = {},
                    forgotToLockEnabled = true,
                    canBypassDnd = false,
                    onToggleForgotToLock = {},
                    onOverrideDndClick = {},
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
                    stage = EBikeStage.NOT_STARTED,
                    onOwnershipYes = {},
                    onToggleEBikeData = {},
                    forgotToLockEnabled = true,
                    canBypassDnd = false,
                    onToggleForgotToLock = {},
                    onOverrideDndClick = {},
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
                    stage = EBikeStage.WAITING,
                    onOwnershipYes = {},
                    onToggleEBikeData = {},
                    forgotToLockEnabled = true,
                    canBypassDnd = false,
                    onToggleForgotToLock = {},
                    onOverrideDndClick = {},
                    onOpenFlow = {},
                )
            }
        }
    }

    @Test
    fun yesNotPermitted() {
        // Bluetooth not granted. The status word and the advice both change,
        // and neither was rendered anywhere before this golden.
        captureRoboImage {
            UiTheme {
                SettingsEBikeContent(
                    navController = rememberNavController(),
                    ownership = EBikeOwnership.YES,
                    eBikeDataEnabled = true,
                    receiving = false,
                    stage = EBikeStage.NOT_PERMITTED,
                    onOwnershipYes = {},
                    onToggleEBikeData = {},
                    forgotToLockEnabled = true,
                    canBypassDnd = false,
                    onToggleForgotToLock = {},
                    onOverrideDndClick = {},
                    onOpenFlow = {},
                )
            }
        }
    }

    @Test
    fun yesNoBondedBike() {
        captureRoboImage {
            UiTheme {
                SettingsEBikeContent(
                    navController = rememberNavController(),
                    ownership = EBikeOwnership.YES,
                    eBikeDataEnabled = true,
                    receiving = false,
                    stage = EBikeStage.NO_BONDED_BIKE,
                    onOwnershipYes = {},
                    onToggleEBikeData = {},
                    forgotToLockEnabled = true,
                    canBypassDnd = false,
                    onToggleForgotToLock = {},
                    onOverrideDndClick = {},
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
                    stage = EBikeStage.RECEIVING,
                    onOwnershipYes = {},
                    onToggleEBikeData = {},
                    forgotToLockEnabled = true,
                    canBypassDnd = false,
                    onToggleForgotToLock = {},
                    onOverrideDndClick = {},
                    onOpenFlow = {},
                )
            }
        }
    }

    @Test
    fun forgotLockDndAllowed() {
        // Forgot-to-lock on AND the channel already overrides DND: the
        // Override-Do-Not-Disturb row shows its "allowed" subtitle.
        captureRoboImage {
            UiTheme {
                SettingsEBikeContent(
                    navController = rememberNavController(),
                    ownership = EBikeOwnership.YES,
                    eBikeDataEnabled = true,
                    receiving = true,
                    stage = EBikeStage.RECEIVING,
                    onOwnershipYes = {},
                    onToggleEBikeData = {},
                    forgotToLockEnabled = true,
                    canBypassDnd = true,
                    onToggleForgotToLock = {},
                    onOverrideDndClick = {},
                    onOpenFlow = {},
                )
            }
        }
    }
}
