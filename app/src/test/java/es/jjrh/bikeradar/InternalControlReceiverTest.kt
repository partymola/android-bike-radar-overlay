// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Smoke tests for InternalControlReceiver: pause/resume drive Prefs;
 * walk-away actions forward to BikeRadarService. The receiver is
 * non-exported and reached via PendingIntent, so a crash here drops
 * pause-from-notification or the walk-away alarm controls.
 */
@RunWith(RobolectricTestRunner::class)
class InternalControlReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val receiver = InternalControlReceiver()

    @Test
    fun ignoresNullAndUnknownActions() {
        receiver.onReceive(app, Intent())
        receiver.onReceive(app, Intent("totally.unknown"))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun pause1hSetsFutureExpiryAndRefreshesNotification() {
        val before = System.currentTimeMillis()
        receiver.onReceive(app, Intent(InternalControlReceiver.ACTION_PAUSE_1H))
        val expiry = Prefs(app).pausedUntilEpochMs
        assertTrue("expiry $expiry should be > before $before", expiry > before)
        // Service should be invoked to refresh the FGS notification.
        val started = shadowOf(app).peekNextStartedService()
        assertNotNull(started)
        assertEquals(BikeRadarService.ACTION_UPDATE_NOTIF, started?.action)
    }

    @Test
    fun resumeClearsExpiry() {
        Prefs(app).pausedUntilEpochMs = System.currentTimeMillis() + 10_000L
        receiver.onReceive(app, Intent(InternalControlReceiver.ACTION_RESUME))
        assertEquals(0L, Prefs(app).pausedUntilEpochMs)
    }

    @Test
    fun walkAwayDismissForwardsToService() {
        receiver.onReceive(app, Intent(InternalControlReceiver.ACTION_WALKAWAY_DISMISS))
        val started = shadowOf(app).peekNextStartedService()
        assertNotNull(started)
        assertEquals(BikeRadarService.ACTION_WALKAWAY_DISMISS, started?.action)
        assertEquals(BikeRadarService::class.java.name, started?.component?.className)
    }

    @Test
    fun walkAwaySnoozeForwardsToService() {
        receiver.onReceive(app, Intent(InternalControlReceiver.ACTION_WALKAWAY_SNOOZE))
        val started = shadowOf(app).peekNextStartedService()
        assertNotNull(started)
        assertEquals(BikeRadarService.ACTION_WALKAWAY_SNOOZE, started?.action)
        assertEquals(BikeRadarService::class.java.name, started?.component?.className)
    }

    @Test
    fun theEndRideIntentCarriesTheActionTheServiceDispatchesOn() {
        // Not a receiver action: the main screen builds this one directly. It
        // is pinned the same way because it is the same kind of wire, and the
        // failure is the same - a renamed action leaves a control that looks
        // live and does nothing when tapped. The literal is asserted rather
        // than the constant, so the constant cannot agree with itself.
        val intent = BikeRadarService.endRideIntent(app)
        assertEquals("es.jjrh.bikeradar.END_RIDE", intent.action)
        assertEquals("es.jjrh.bikeradar.END_RIDE", BikeRadarService.ACTION_END_RIDE)
        assertEquals(BikeRadarService::class.java.name, intent.component?.className)
    }
}
