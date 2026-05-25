// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.data.DashcamOwnership
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the onboarding [PairingStepContent] leaf.
 *
 * The full state space is 2 (radar bonded?) × 4 (dashcam ownership ×
 * picked?). We snapshot a representative subset that covers each
 * dashcam sub-state at least once and both radar-bond states:
 *  - radarUnbondedDashcamUnanswered: pre-pair landing state
 *  - radarUnbondedDashcamSkipped: user said "no dashcam" but hasn't
 *    paired the radar yet (Finish footer warns)
 *  - radarBondedDashcamUnanswered: radar paired, dashcam still asking
 *  - radarBondedDashcamPicked: full happy path with both rows resolved
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class OnboardingPairingStepSnapshotTest {

    @Test
    fun radarUnbondedDashcamUnanswered() {
        captureRoboImage {
            UiTheme {
                PairingStepContent(
                    radarBonded = false,
                    radarLocalName = null,
                    radarMac = null,
                    dashcamOwnership = DashcamOwnership.UNANSWERED,
                    dashcamMac = null,
                    dashcamDisplayName = null,
                    onOpenBluetoothSettings = {},
                    onPickDashcam = {},
                    onDashcamSkip = {},
                    onDashcamReclaim = {},
                    onFinish = {},
                )
            }
        }
    }

    @Test
    fun radarUnbondedDashcamSkipped() {
        captureRoboImage {
            UiTheme {
                PairingStepContent(
                    radarBonded = false,
                    radarLocalName = null,
                    radarMac = null,
                    dashcamOwnership = DashcamOwnership.NO,
                    dashcamMac = null,
                    dashcamDisplayName = null,
                    onOpenBluetoothSettings = {},
                    onPickDashcam = {},
                    onDashcamSkip = {},
                    onDashcamReclaim = {},
                    onFinish = {},
                )
            }
        }
    }

    @Test
    fun radarBondedDashcamUnanswered() {
        captureRoboImage {
            UiTheme {
                PairingStepContent(
                    radarBonded = true,
                    radarLocalName = "RearVue8",
                    radarMac = "AA:BB:CC:DD:EE:01",
                    dashcamOwnership = DashcamOwnership.UNANSWERED,
                    dashcamMac = null,
                    dashcamDisplayName = null,
                    onOpenBluetoothSettings = {},
                    onPickDashcam = {},
                    onDashcamSkip = {},
                    onDashcamReclaim = {},
                    onFinish = {},
                )
            }
        }
    }

    @Test
    fun radarBondedDashcamPicked() {
        captureRoboImage {
            UiTheme {
                PairingStepContent(
                    radarBonded = true,
                    radarLocalName = "RearVue8",
                    radarMac = "AA:BB:CC:DD:EE:01",
                    dashcamOwnership = DashcamOwnership.YES,
                    dashcamMac = "AA:BB:CC:DD:EE:02",
                    dashcamDisplayName = "Front cam",
                    onOpenBluetoothSettings = {},
                    onPickDashcam = {},
                    onDashcamSkip = {},
                    onDashcamReclaim = {},
                    onFinish = {},
                )
            }
        }
    }
}
