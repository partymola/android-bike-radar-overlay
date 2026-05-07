// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import es.jjrh.bikeradar.data.DashcamOwnership
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi goldens for the onboarding [PairingStepContent] leaf.
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
 * CI does not run these — Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class OnboardingPairingStepSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    @Test
    fun radarUnbondedDashcamUnanswered() {
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
