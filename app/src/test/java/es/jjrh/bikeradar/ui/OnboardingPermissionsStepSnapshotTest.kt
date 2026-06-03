// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.Manifest
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the onboarding [PermissionsStepContent] leaf.
 * Renders pre-resolved (spec, granted) lists so the test does not need
 * a [LocalContext] or a real lifecycle observer.
 *
 * Variants exercise the three states the Continue button cares about:
 *  - all required perms granted (Continue enabled, optional still off)
 *  - mixed (one required granted, one not - Continue disabled)
 *  - all pending (every card in denied state - Continue disabled)
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class OnboardingPermissionsStepSnapshotTest {

    /**
     * Inert ActivityResultRegistryOwner so PermissionCard's
     * rememberLauncherForActivityResult doesn't crash under Robolectric.
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
        permissions = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ),
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

    @Test
    fun allGranted() {
        val states = listOf(nearby to true, notifications to true, overlay to true)
        captureRoboImage {
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
        captureRoboImage {
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
        captureRoboImage {
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
