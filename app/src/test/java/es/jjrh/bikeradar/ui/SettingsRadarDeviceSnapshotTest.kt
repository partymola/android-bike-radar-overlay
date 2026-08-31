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
 * [SettingsRadarDeviceContent] leaf. Locks the four states: live, no-signal
 * (offline), never-paired (pair prompt), and the ambiguous multi-radar
 * selection list.
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
                    link = DeviceLinkState.LIVE,
                    batteryPct = 78,
                )
            }
        }
    }

    @Test
    fun limitedSourceNote() {
        // The only visible state this screen gained, and the one whose LAYOUT
        // is the risk rather than its presence: the note is several wrapped
        // lines of caption inside the name column, beside a vertically-centred
        // icon. A presence assertion proves it composes; only a golden shows
        // whether the card still reads as a card.
        captureRoboImage {
            UiTheme {
                SettingsRadarDeviceContent(
                    onBack = {},
                    bonded = listOf(radarA),
                    chosenMac = null,
                    activeName = radarA.name,
                    link = DeviceLinkState.LIMITED,
                    batteryPct = 78,
                )
            }
        }
    }

    @Test
    fun mountOffsetRight() {
        // Non-zero mount offset: exercises the worded "N cm right" readout and
        // the off-centre slider thumb (every other snapshot sits at Centred).
        captureRoboImage {
            UiTheme {
                SettingsRadarDeviceContent(
                    onBack = {},
                    bonded = listOf(radarA),
                    chosenMac = null,
                    activeName = radarA.name,
                    link = DeviceLinkState.LIVE,
                    batteryPct = 78,
                    offsetCm = 15,
                )
            }
        }
    }

    @Test
    fun firmwareRevisionDisplayed() {
        // Firmware known: the device card grows the software line. Every
        // other snapshot passes firmwareRev = null.
        captureRoboImage {
            UiTheme {
                SettingsRadarDeviceContent(
                    onBack = {},
                    bonded = listOf(radarA),
                    chosenMac = null,
                    activeName = radarA.name,
                    link = DeviceLinkState.LIVE,
                    batteryPct = 78,
                    firmwareRev = "6.70",
                )
            }
        }
    }

    @Test
    fun connecting() {
        // The link is up but the setup has not produced data yet - the state
        // that used to render as the false "Not in range".
        captureRoboImage {
            UiTheme {
                SettingsRadarDeviceContent(
                    onBack = {},
                    bonded = listOf(radarA),
                    chosenMac = null,
                    activeName = radarA.name,
                    link = DeviceLinkState.CONNECTING,
                    batteryPct = null,
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
                    link = DeviceLinkState.NO_SIGNAL,
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
                    link = DeviceLinkState.NOT_PAIRED,
                    batteryPct = null,
                )
            }
        }
    }

    @Test
    fun othersEscapeHatch() {
        // The collapsed "My radar isn't listed" row, shown when bonded
        // devices exist that the radar name heuristic doesn't recognise.
        captureRoboImage {
            UiTheme {
                SettingsRadarDeviceContent(
                    onBack = {},
                    bonded = listOf(radarA),
                    chosenMac = null,
                    activeName = radarA.name,
                    link = DeviceLinkState.NO_SIGNAL,
                    batteryPct = null,
                    others = listOf(
                        RadarSelection.BondedRadar("CC:CC:CC:CC:CC:CC", "Pixel Watch"),
                        RadarSelection.BondedRadar("DD:DD:DD:DD:DD:DD", "OffBrandRadar"),
                    ),
                )
            }
        }
    }

    @Test
    fun twoRadarsNonePinned() {
        // The genuinely ambiguous case, which `ambiguousTwoRadars` below does
        // NOT render (it pins one). The app streams from a name-matched radar
        // while no name can be resolved, so the title falls to "Not selected"
        // over a live status line. That pair is the visible result of asking
        // "is there a radar" separately from "which radar", and it appeared
        // in no golden.
        captureRoboImage {
            UiTheme {
                SettingsRadarDeviceContent(
                    onBack = {},
                    bonded = listOf(radarA, radarB),
                    chosenMac = null,
                    activeName = null,
                    link = DeviceLinkState.LIVE,
                    batteryPct = 82,
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
                    link = DeviceLinkState.LIVE,
                    batteryPct = 82,
                )
            }
        }
    }
}
