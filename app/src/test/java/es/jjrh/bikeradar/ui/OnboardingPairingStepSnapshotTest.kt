// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.RadarSelection.BondedRadar
import es.jjrh.bikeradar.data.DashcamOwnership
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the onboarding [PairingStepContent] leaf.
 *
 * The full state space is 2 (radar bonded?) × 4 (dashcam ownership ×
 * picked?), plus the device pick. We snapshot a representative subset that
 * covers each dashcam sub-state at least once and both radar-bond states:
 *  - radarUnbondedDashcamUnanswered: pre-pair landing state
 *  - radarUnbondedDashcamSkipped: user said "no dashcam" but hasn't
 *    paired the radar yet (Finish footer warns)
 *  - radarUnbondedOthersPaired (and its Spanish twin): the device pick
 *  - radarBondedNoneSelected (and its Spanish twin): two radars, none picked
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

    // No radar recognised, other devices paired: the device pick shows.
    @Test
    fun radarUnbondedOthersPaired() {
        captureRoboImage { OthersPaired() }
    }

    @Test
    @Config(qualifiers = "es-w448dp-h997dp-xxhdpi")
    fun radarUnbondedOthersPairedEs() {
        captureRoboImage { OthersPaired() }
    }

    // Two radars paired and none picked: the card reads PAIRED, Not selected,
    // and the pick below is titled as a choice.
    @Test
    fun radarBondedNoneSelected() {
        captureRoboImage { NoneSelected() }
    }

    @Test
    @Config(qualifiers = "es-w448dp-h997dp-xxhdpi")
    fun radarBondedNoneSelectedEs() {
        captureRoboImage { NoneSelected() }
    }

    @Composable
    private fun NoneSelected() {
        UiTheme {
            PairingStepContent(
                radarBonded = true,
                radarLocalName = null,
                radarMac = null,
                pickDevices = listOf(
                    BondedRadar("AA:BB:CC:DD:EE:01", "RearVue8"),
                    BondedRadar("AA:BB:CC:DD:EE:05", "RTL515"),
                ),
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

    @Composable
    private fun OthersPaired() {
        UiTheme {
            PairingStepContent(
                radarBonded = false,
                radarLocalName = null,
                radarMac = null,
                pickDevices = listOf(
                    BondedRadar("AA:BB:CC:DD:EE:03", "Watch 7"),
                    BondedRadar("AA:BB:CC:DD:EE:04", "BackLight 9"),
                ),
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
