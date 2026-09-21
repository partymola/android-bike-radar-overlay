// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /**
     * The riding-aid notice is in front of every other destination, so a test
     * that leaves it unacknowledged composes THAT rather than the screen it
     * names. Each test below says which side of the gate it is on.
     */
    @Test
    fun onboardingStartFreshInstall() {
        // First-run install past the notice: Prefs otherwise default. The
        // activity must onCreate without throwing AND must not enqueue a
        // foreground-service start (no perms, not past onboarding).
        Prefs(app).safetyNoticeAcknowledged = true
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.create().start().resume()
            assertNull(
                "onboarding state must not start the FGS",
                shadowOf(app).peekNextStartedService(),
            )
        }
    }

    /**
     * Composing an unacknowledged install must not itself acknowledge it. The
     * ROUTE is pinned by `SafetyNoticeGateTest` and by the on-screen
     * assertions in `SafetyNoticeAcknowledgeTest`; this only holds the flag
     * still, which is what a rider's second launch depends on.
     */
    @Test
    fun composingTheNoticeDoesNotAcknowledgeIt() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.create().start().resume()
            assertFalse(Prefs(app).safetyNoticeAcknowledged)
        }
    }

    /**
     * An upgrading rider's first 1.6.0 launch: past onboarding, permissions
     * granted, notice not yet acknowledged. The radar service STILL starts.
     *
     * Deliberate, and worth a test in its own right because the opposite is
     * the tempting reading of a gate: withholding the service until the tap
     * would kill the radar of a rider who opens the app mid-ride, which is
     * exactly when they need it. The notice gates the UI, never the service.
     */
    @Test
    fun theServiceStillStartsWhileTheNoticeIsUp() {
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
            controller.create().start().resume()
            val started = shadowOf(app).peekNextStartedService()
            assertNotNull("the notice must not withhold the radar service", started)
            assertEquals(BikeRadarService::class.java.name, started?.component?.className)
            assertFalse("and it must still be unacknowledged", Prefs(app).safetyNoticeAcknowledged)
        }
    }

    @Test
    fun returningUserStartsServiceWhenAllGatesPass() {
        Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
            safetyNoticeAcknowledged = true
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
            safetyNoticeAcknowledged = true
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
