// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
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

    // The production specs, so the goldens show the marks the screen shows.
    private val nearby = PERMISSIONS.single { Manifest.permission.BLUETOOTH_CONNECT in it.permissions }
    private val notifications = PERMISSIONS.single { it.titleRes == R.string.permission_notifications_title }
    private val overlay = PERMISSIONS.single { it.permissions.isEmpty() }
    private val location = PERMISSIONS.single { Manifest.permission.ACCESS_COARSE_LOCATION in it.permissions }

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
