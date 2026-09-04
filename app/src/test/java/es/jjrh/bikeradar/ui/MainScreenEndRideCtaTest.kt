// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The End ride control's offer rule, driven through the real [ctaFor].
 *
 * [es.jjrh.bikeradar.RadarLinkStatus.canEndRide] already pins WHEN the offer is
 * allowed. This pins that the screen actually asks it, and that a true answer
 * produces a control rather than the null the live-and-well branch returns.
 * Without it the predicate could be correct while nothing on screen used it,
 * which is the shape the repo's "pin the wiring, not the pure core" rule names.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenEndRideCtaTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** The live-and-well inputs, which on their own yield no CTA. Every earlier
     *  branch in [ctaFor] is deliberately satisfied so the End ride branch is
     *  the only one that can fire. */
    private fun liveInputs() = MainStatusInputs(
        firstRunComplete = true,
        pausedUntilEpochMs = 0L,
        hasBond = true,
        radarFresh = true,
        haErrorRecent = false,
        dashcamOwned = false,
        dashcamWarnWhenOff = false,
        dashcamFresh = false,
        dashcamDisplayName = null,
        serviceEnabled = true,
        bluetoothEnabled = true,
    )

    @Composable
    private fun ctaWith(canEndRide: Boolean): StatusCta? = ctaFor(
        inputs = liveInputs(),
        nowMs = 1_000L,
        navController = rememberNavController(),
        ctx = app,
        prefs = Prefs(app),
        canEndRide = canEndRide,
    )

    @Test
    fun aDownRadarAfterARideOffersTheControl() {
        var label: String? = null
        compose.setContent { label = ctaWith(canEndRide = true)?.label }
        compose.waitForIdle()
        // The literal, not the resource: the label is the whole rider-facing
        // contract of this control, and it must not silently become something
        // that promises to save or close the ride record.
        assertEquals("I've parked", label)
    }

    @Test
    fun aLiveRadarOffersNothing() {
        // The same inputs with the gate shut must fall through to the
        // live-and-well branch, which carries no CTA at all. A control that
        // silences a safety cue must not be reachable mid-ride.
        var cta: StatusCta? = null
        var evaluated = false
        compose.setContent {
            cta = ctaWith(canEndRide = false)
            evaluated = true
        }
        compose.waitForIdle()
        assertEquals("the branch must have been reached", true, evaluated)
        assertNull("no CTA while the radar is live", cta)
    }
}
