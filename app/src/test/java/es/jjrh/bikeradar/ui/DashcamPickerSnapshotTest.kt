// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi goldens for the [DashcamPickerContent] leaf — the list +
 * footer surface of the dashcam picker. The modal Dialog chrome stays
 * in the body so isn't snapshot-tested.
 *
 * Variants:
 *  - empty: no bonded devices (only the "None" radio + pair-CTA)
 *  - likelyMatchesPresent: a Vue-named device appears under "Likely
 *    matches"
 *  - otherPairedOnly: a paired device with no heuristic hit, under
 *    "Other paired devices"
 *  - rowSelected: same as likelyMatchesPresent but with the matching
 *    row selected so Save flips to enabled
 *
 * CI does not run these — Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class DashcamPickerSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    private val vueCandidate = DashcamCandidate(
        mac = "AA:BB:CC:DD:EE:01",
        name = "VUE-CAM-001",
        likely = true,
    )

    private val otherCandidate = DashcamCandidate(
        mac = "11:22:33:44:55:66",
        name = "Pixel Buds Pro",
        likely = false,
    )

    @Test
    fun empty() {
        paparazzi.snapshot {
            UiTheme {
                DashcamPickerContent(
                    devices = emptyList(),
                    selectedMac = null,
                    saveEnabled = false,
                    onSelect = {},
                    onOpenBluetoothSettings = {},
                    onCancel = {},
                    onSave = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun likelyMatchesPresent() {
        paparazzi.snapshot {
            UiTheme {
                DashcamPickerContent(
                    devices = listOf(vueCandidate),
                    selectedMac = null,
                    saveEnabled = false,
                    onSelect = {},
                    onOpenBluetoothSettings = {},
                    onCancel = {},
                    onSave = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun otherPairedOnly() {
        paparazzi.snapshot {
            UiTheme {
                DashcamPickerContent(
                    devices = listOf(otherCandidate),
                    selectedMac = null,
                    saveEnabled = false,
                    onSelect = {},
                    onOpenBluetoothSettings = {},
                    onCancel = {},
                    onSave = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun rowSelected() {
        paparazzi.snapshot {
            UiTheme {
                DashcamPickerContent(
                    devices = listOf(vueCandidate),
                    selectedMac = vueCandidate.mac,
                    saveEnabled = true,
                    onSelect = {},
                    onOpenBluetoothSettings = {},
                    onCancel = {},
                    onSave = {},
                    onBack = {},
                )
            }
        }
    }
}
