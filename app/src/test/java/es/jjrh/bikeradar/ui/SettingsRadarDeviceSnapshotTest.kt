// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.RadarSelection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the Radar device-link screen, via the stateless
 * [SettingsRadarDeviceContent] leaf. Locks the four states: connected,
 * not-in-range (offline), never-paired (pair prompt), and the ambiguous
 * multi-radar selection list.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsRadarDeviceSnapshotTest {

    private val radarA = RadarSelection.BondedRadar("AA:AA:AA:AA:AA:AA", "RearVue8")
    private val radarB = RadarSelection.BondedRadar("BB:BB:BB:BB:BB:BB", "RTL515 (spare)")

    @Test
    fun connected() {
        captureRoboImage {
            UiTheme {
                SettingsRadarDeviceContent(
                    onBack = {},
                    bonded = listOf(radarA),
                    chosenMac = null,
                    activeName = radarA.name,
                    connected = true,
                    batteryPct = 78,
                )
            }
        }
    }

    @Test
    fun offline() {
        captureRoboImage {
            UiTheme {
                SettingsRadarDeviceContent(
                    onBack = {},
                    bonded = listOf(radarA),
                    chosenMac = null,
                    activeName = radarA.name,
                    connected = false,
                    batteryPct = null,
                )
            }
        }
    }

    @Test
    fun neverPaired() {
        captureRoboImage {
            UiTheme {
                SettingsRadarDeviceContent(
                    onBack = {},
                    bonded = emptyList(),
                    chosenMac = null,
                    activeName = null,
                    connected = false,
                    batteryPct = null,
                )
            }
        }
    }

    @Test
    fun ambiguousTwoRadars() {
        captureRoboImage {
            UiTheme {
                SettingsRadarDeviceContent(
                    onBack = {},
                    bonded = listOf(radarA, radarB),
                    chosenMac = radarA.mac,
                    activeName = radarA.name,
                    connected = true,
                    batteryPct = 82,
                )
            }
        }
    }
}
