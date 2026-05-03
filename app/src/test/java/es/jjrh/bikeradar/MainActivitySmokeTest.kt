// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Smoke tests for [MainActivity.onCreate]. Drives the activity through
 * Robolectric's controller so the synchronous portion of onCreate runs
 * end-to-end against a real merged AndroidManifest, the real Compose
 * setup, and the real navigation graph. Catches synchronous boot
 * failures (manifest activity declaration, NavHost wiring, immediate
 * Compose composition crash on the start destination).
 *
 * Does NOT exercise: post-RESUMED `LaunchedEffect` bodies that wait on
 * delays, lifecycle-aware flow collectors that only emit after RESUMED,
 * or anything past the first Compose recomposition.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivitySmokeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun installInMemoryCryptor() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
    }

    @After
    fun restoreCryptorFactory() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    @Test
    fun onboardingStartFreshInstall() {
        // First-run install: Prefs default. The activity must onCreate
        // without throwing AND must not enqueue a foreground-service
        // start (no perms, not past onboarding).
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.create().start().resume()
            assertNull(
                "onboarding state must not start the FGS",
                shadowOf(app).peekNextStartedService(),
            )
        }
    }

    @Test
    fun returningUserStartsServiceWhenAllGatesPass() {
        Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
        }
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.create()
            val started = shadowOf(app).peekNextStartedService()
            assertNotNull("service should be enqueued from MainActivity.onCreate", started)
            assertEquals(BikeRadarService::class.java.name, started?.component?.className)
        }
    }

    @Test
    fun returningUserDoesNotStartServiceWhenServiceDisabled() {
        Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = false
        }
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.create()
            assertNull(shadowOf(app).peekNextStartedService())
        }
    }
}
