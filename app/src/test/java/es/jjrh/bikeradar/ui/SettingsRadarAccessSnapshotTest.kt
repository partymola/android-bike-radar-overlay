// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.access.RadarGrant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens for the list of apps allowed to use the radar.
 *
 * Variants:
 *  - empty: nothing has ever been allowed
 *  - readingOnly: an app that can see the radar and nothing else
 *  - mixed: one reader and one that can also change the tail light, so the
 *    two levels of trust are visibly different rather than one label
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsRadarAccessSnapshotTest {

    private fun grant(pkg: String, label: String, read: Boolean, control: Boolean) = RadarGrant(pkg, "aa11", label, 0L, 0L, read, control)

    @Test
    fun empty() {
        captureRoboImage {
            UiTheme { SettingsRadarAccessContent(grants = emptyList(), onRevoke = {}, onBack = {}) }
        }
    }

    @Test
    fun readingOnly() {
        captureRoboImage {
            UiTheme {
                SettingsRadarAccessContent(
                    grants = listOf(grant("com.example.trailbuddy", "Trail Buddy", read = true, control = false)),
                    onRevoke = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun mixed() {
        captureRoboImage {
            UiTheme {
                SettingsRadarAccessContent(
                    grants = listOf(
                        grant("com.example.trailbuddy", "Trail Buddy", read = true, control = false),
                        grant("com.example.other", "Another Navigator", read = true, control = true),
                    ),
                    onRevoke = {},
                    onBack = {},
                )
            }
        }
    }

    /**
     * Spanish, on the row that is already the tightest here: a package name,
     * both capability phrases and a last-used stamp joined into one 12sp
     * subtitle. The Spanish capability phrase is the longest of the four, so
     * this is where the joined line runs out of width first.
     */
    @Test
    @Config(qualifiers = "+es")
    fun mixedEs() {
        captureRoboImage {
            UiTheme {
                SettingsRadarAccessContent(
                    grants = listOf(
                        grant("com.example.trailbuddy", "Trail Buddy", read = true, control = false),
                        grant("com.example.other", "Another Navigator", read = true, control = true),
                    ),
                    onRevoke = {},
                    onBack = {},
                )
            }
        }
    }
}
