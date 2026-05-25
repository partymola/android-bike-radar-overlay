// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi golden for the open-source-licences screen. Renders the
 * top of the screen - header, intro blurb, and first section groups.
 * Mid-scroll positions are not snapshotted: the lambda capture cannot drive a
 * `verticalScroll(rememberScrollState())` mid-test, and the layout
 * structure is uniform enough that the top frame catches regressions
 * to the row template.
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsLicensesSnapshotTest {

    @Test
    fun top() {
        captureRoboImage {
            SettingsLicenses(navController = rememberNavController())
        }
    }
}
