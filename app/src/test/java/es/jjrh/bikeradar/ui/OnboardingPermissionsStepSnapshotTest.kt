// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.Manifest
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi goldens for the onboarding [PermissionsStepContent] leaf.
 * Renders pre-resolved (spec, granted) lists so the test does not need
 * a [LocalContext] or a real lifecycle observer.
 *
 * Variants exercise the three states the Continue button cares about:
 *  - all required perms granted (Continue enabled, optional still off)
 *  - mixed (one required granted, one not — Continue disabled)
 *  - all pending (every card in denied state — Continue disabled)
 *
 * CI does not run these — Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class OnboardingPermissionsStepSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    /**
     * Inert ActivityResultRegistryOwner so PermissionCard's
     * rememberLauncherForActivityResult doesn't crash under Paparazzi.
     * The launcher is never invoked from a snapshot.
     */
    private val fakeRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: androidx.core.app.ActivityOptionsCompat?,
            ) {}
        }
    }

    private val nearby = PermissionSpec(
        permissions = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
        title = "Nearby devices",
        rationale = "Scan for and connect to your radar and dashcam over Bluetooth.",
        required = true,
    )

    private val notifications = PermissionSpec(
        permissions = listOf("android.permission.POST_NOTIFICATIONS"),
        title = "Notifications",
        rationale = "Post the silent service notification and any ride alerts.",
        required = true,
    )

    private val overlay = PermissionSpec(
        permissions = emptyList(),
        title = "Draw over other apps",
        rationale = "Draw the radar overlay on top of whatever's on screen. " +
            "Without this, alerts still play but you won't see the overlay.",
        required = false,
        markLabel = "Recommended",
    )

    @Test
    fun allGranted() {
        val states = listOf(nearby to true, notifications to true, overlay to true)
        paparazzi.snapshot {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeRegistryOwner) {
                UiTheme {
                    PermissionsStepContent(
                        states = states,
                        requiredGranted = true,
                        onContinue = {},
                        onPermissionChanged = {},
                    )
                }
            }
        }
    }

    @Test
    fun mixed() {
        val states = listOf(nearby to true, notifications to false, overlay to false)
        paparazzi.snapshot {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeRegistryOwner) {
                UiTheme {
                    PermissionsStepContent(
                        states = states,
                        requiredGranted = false,
                        onContinue = {},
                        onPermissionChanged = {},
                    )
                }
            }
        }
    }

    @Test
    fun allPending() {
        val states = listOf(nearby to false, notifications to false, overlay to false)
        paparazzi.snapshot {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeRegistryOwner) {
                UiTheme {
                    PermissionsStepContent(
                        states = states,
                        requiredGranted = false,
                        onContinue = {},
                        onPermissionChanged = {},
                    )
                }
            }
        }
    }
}
