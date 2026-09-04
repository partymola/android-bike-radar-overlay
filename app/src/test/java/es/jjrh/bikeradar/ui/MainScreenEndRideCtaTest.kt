// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The End ride control, driven through the real [ctaFor]: when it is offered,
 * when it is withheld, and what a tap on it does.
 *
 * [es.jjrh.bikeradar.RadarLinkStatus.canEndRide] already pins WHEN the offer is
 * allowed. This pins that the screen actually asks it, that the answer survives
 * the branch's own freshness term, and that the click reaches the service.
 * Without them the predicate could be correct while nothing on screen used it.
 *
 * The four states of the two terms the branch reads are covered one test each:
 * either alone answering every case would leave the other unpinned.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenEndRideCtaTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Inputs with every branch of [ctaFor] ABOVE End ride deliberately
     *  satisfied, so that branch is the only one that can fire. Both terms it
     *  reads are passed explicitly: the state under test is the pair. */
    @Composable
    private fun ctaWith(rideEndOfferable: Boolean, radarFresh: Boolean): StatusCta? = ctaFor(
        inputs = MainStatusInputs(
            firstRunComplete = true,
            pausedUntilEpochMs = 0L,
            hasBond = true,
            radarFresh = radarFresh,
            haErrorRecent = false,
            dashcamOwned = false,
            dashcamWarnWhenOff = false,
            dashcamFresh = false,
            dashcamDisplayName = null,
            serviceEnabled = true,
            bluetoothEnabled = true,
            rideEndOfferable = rideEndOfferable,
        ),
        nowMs = 1_000L,
        navController = rememberNavController(),
        ctx = app,
        prefs = Prefs(app),
    )

    @Test
    fun aDownRadarAfterARideOffersTheControl() {
        var label: String? = null
        compose.setContent { label = ctaWith(rideEndOfferable = true, radarFresh = false)?.label }
        compose.waitForIdle()
        // The literal, not the resource: the label is the whole rider-facing
        // contract of this control, and it must not silently become something
        // that promises to save or close the ride record.
        assertEquals("I've parked", label)
    }

    @Test
    fun tappingTheControlAsksTheServiceToEndTheRide() {
        // The only test that runs the click body. Replacing it with a no-op
        // leaves a control that renders, reads live and declares nothing; the
        // service side of the chain is pinned by
        // BikeRadarServiceSmokeTest.theEndRideActionReachesTheCoordinator.
        var cta: StatusCta? = null
        compose.setContent { cta = ctaWith(rideEndOfferable = true, radarFresh = false) }
        compose.waitForIdle()
        assertNotNull("the End ride branch must have fired", cta)
        cta!!.onClick()

        // The literal rather than the constant, so the action cannot agree
        // with itself while naming something the service never dispatches on.
        val started = shadowOf(app).peekNextStartedService()
        assertEquals("es.jjrh.bikeradar.END_RIDE", started?.action)
        assertEquals(BikeRadarService::class.java.name, started?.component?.className)
    }

    @Test
    fun aLiveRadarOffersNothing() {
        // The same inputs with the gate shut must fall through to the
        // live-and-well branch, which carries no CTA at all. A control that
        // silences a safety cue must not be reachable mid-ride.
        var cta: StatusCta? = null
        var evaluated = false
        compose.setContent {
            cta = ctaWith(rideEndOfferable = false, radarFresh = true)
            evaluated = true
        }
        compose.waitForIdle()
        assertEquals("the branch must have been reached", true, evaluated)
        assertNull("no CTA while the radar is live", cta)
    }

    @Test
    fun aBriefMidRideDropOffersNothing() {
        // The fourth state, and the one the offer threshold exists for: a
        // routine BLE blip leaves the radar stale but under the gate. Without
        // this the offer term itself is unpinned, since the freshness term
        // alone answers every other case, and a full-width control that
        // silences the drop cue would appear at every mid-ride drop.
        var cta: StatusCta? = null
        var evaluated = false
        compose.setContent {
            cta = ctaWith(rideEndOfferable = false, radarFresh = false)
            evaluated = true
        }
        compose.waitForIdle()
        assertEquals("the branch must have been reached", true, evaluated)
        assertNull("a sub-threshold drop must not be offered the control", cta)
    }

    @Test
    fun aRadarStillReadingLiveIsNeverOfferedTheControl() {
        // The congruence pin: an offer the hero would not make must not reach
        // the button, or a green "Radar live" card carries an "I've parked"
        // control. A release build reaches this once dev mode is unlocked,
        // because Replay and Synthetic publish frames with no link, so the
        // radar reads live while the off-instant still stands.
        var cta: StatusCta? = null
        var evaluated = false
        compose.setContent {
            cta = ctaWith(rideEndOfferable = true, radarFresh = true)
            evaluated = true
        }
        compose.waitForIdle()
        assertEquals("the branch must have been reached", true, evaluated)
        assertNull("a live radar must not be offered the parked control", cta)
    }
}
