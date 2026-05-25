// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the [DashcamPickerContent] leaf - the list +
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
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class DashcamPickerSnapshotTest {

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
        captureRoboImage {
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
        captureRoboImage {
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
        captureRoboImage {
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
        captureRoboImage {
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
