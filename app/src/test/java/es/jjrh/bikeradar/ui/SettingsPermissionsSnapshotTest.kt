// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.Manifest
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the Settings → Permissions screen using the
 * stateless [SettingsPermissionsContent] leaf so the test does not
 * depend on real `Context`-backed permission state.
 *
 * Variants cover the card-mix states a fresh-install user can actually land
 * in - everything granted, a mix, everything denied - plus the location
 * card's two coordinates-set states and a Spanish render.
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsPermissionsSnapshotTest {

    private val nearby = PermissionSpec(
        permissions = listOf("android.permission.BLUETOOTH_SCAN"),
        titleRes = R.string.permission_nearby_title,
        rationaleRes = R.string.permission_nearby_rationale,
        required = true,
    )

    private val notifications = PermissionSpec(
        permissions = listOf("android.permission.POST_NOTIFICATIONS"),
        titleRes = R.string.permission_notifications_title,
        rationaleRes = R.string.permission_notifications_rationale,
        required = true,
    )

    private val overlay = PermissionSpec(
        permissions = emptyList(),
        titleRes = R.string.permission_overlay_title,
        rationaleRes = R.string.permission_overlay_rationale,
        required = false,
        markLabelRes = R.string.permission_mark_recommended,
    )

    private val location = PermissionSpec(
        permissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
        titleRes = R.string.permission_location_title,
        rationaleRes = R.string.permission_location_rationale,
        required = false,
        markLabelRes = R.string.common_optional,
    )

    @Test
    fun allGranted() {
        captureRoboImage {
            UiTheme {
                SettingsPermissionsContent(
                    navController = rememberNavController(),
                    specsAndGranted = listOf(
                        nearby to true,
                        notifications to true,
                        overlay to true,
                        location to true,
                    ),
                )
            }
        }
    }

    @Test
    fun mixed() {
        captureRoboImage {
            UiTheme {
                SettingsPermissionsContent(
                    navController = rememberNavController(),
                    specsAndGranted = listOf(
                        nearby to true,
                        notifications to false,
                        overlay to false,
                        location to false,
                    ),
                )
            }
        }
    }

    @Test
    fun allDenied() {
        captureRoboImage {
            UiTheme {
                SettingsPermissionsContent(
                    navController = rememberNavController(),
                    specsAndGranted = listOf(
                        nearby to false,
                        notifications to false,
                        overlay to false,
                        location to false,
                    ),
                )
            }
        }
    }

    /**
     * Coordinates set but location ungranted: the card shows them instead of
     * the "or / Enter coordinates" choice. Non-London coordinates, so the
     * value row is visibly distinct from the fallback the rationale names.
     */
    @Test
    fun locationManualSet() {
        captureRoboImage {
            UiTheme {
                SettingsPermissionsContent(
                    navController = rememberNavController(),
                    specsAndGranted = listOf(
                        nearby to true,
                        notifications to true,
                        overlay to true,
                        location to false,
                    ),
                    manualLocationSummary = "-33.8688, 151.2093",
                )
            }
        }
    }

    /** Granted AND overridden - manual coordinates beat GPS in the resolver. */
    @Test
    fun locationGrantedWithManualSet() {
        captureRoboImage {
            UiTheme {
                SettingsPermissionsContent(
                    navController = rememberNavController(),
                    specsAndGranted = listOf(
                        nearby to true,
                        notifications to true,
                        overlay to true,
                        location to true,
                    ),
                    manualLocationSummary = "-33.8688, 151.2093",
                )
            }
        }
    }

    /**
     * Spanish, where the rationale runs ~25% longer than English and the value
     * row's Cambiar/Borrar are wider than Change/Clear. Wider than the London
     * pair, so the monospace column is not at its narrowest.
     */
    @Test
    @Config(qualifiers = "+es")
    fun locationManualSetEs() {
        captureRoboImage {
            UiTheme {
                SettingsPermissionsContent(
                    navController = rememberNavController(),
                    specsAndGranted = listOf(
                        nearby to true,
                        notifications to true,
                        overlay to true,
                        location to false,
                    ),
                    manualLocationSummary = "-33.8688, 151.2093",
                )
            }
        }
    }
}
