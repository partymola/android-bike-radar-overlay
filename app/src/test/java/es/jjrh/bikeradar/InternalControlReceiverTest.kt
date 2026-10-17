// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Smoke tests for InternalControlReceiver: pause/resume drive Prefs; every
 * button reaches a running BikeRadarService, and none starts one once it has
 * stopped. The receiver is non-exported and reached via PendingIntent, so a
 * crash here drops pause-from-notification or the walk-away alarm controls.
 */
@RunWith(RobolectricTestRunner::class)
class InternalControlReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val receiver = InternalControlReceiver()

    @After
    fun noServiceLeftRunning() {
        BikeRadarService.radarLinkStateForUi = null
    }

    /** What a running service publishes; the receiver reads it as "running". */
    private fun serviceRunning() {
        BikeRadarService.radarLinkStateForUi = MutableStateFlow(RadarLinkState())
    }

    private val nm get() = app.getSystemService(NotificationManager::class.java)

    private fun journal() = LinkEventJournal({ app.getExternalFilesDir(null) }).readTail()

    @Test
    fun ignoresNullAndUnknownActions() {
        receiver.onReceive(app, Intent())
        receiver.onReceive(app, Intent("totally.unknown"))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun pause1hSetsFutureExpiryAndRefreshesNotification() {
        serviceRunning()
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
    fun resumeClearsExpiryAndRefreshesNotification() {
        serviceRunning()
        Prefs(app).pausedUntilEpochMs = System.currentTimeMillis() + 10_000L
        receiver.onReceive(app, Intent(InternalControlReceiver.ACTION_RESUME))
        assertEquals(0L, Prefs(app).pausedUntilEpochMs)
        assertEquals(BikeRadarService.ACTION_UPDATE_NOTIF, shadowOf(app).peekNextStartedService()?.action)
    }

    @Test
    fun pauseOrResumeWithNoServiceRunningStartsNone() {
        // The buttons live on the service's own notification, so with no
        // service there is nothing to refresh, and a refresh would start one.
        receiver.onReceive(app, Intent(InternalControlReceiver.ACTION_PAUSE_1H))
        assertTrue("the pause still applies", Prefs(app).pausedUntilEpochMs > 0L)
        receiver.onReceive(app, Intent(InternalControlReceiver.ACTION_RESUME))
        assertEquals(0L, Prefs(app).pausedUntilEpochMs)
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun aWalkAwayAlertLeftWithNoServiceRunningStartsNone() {
        // A service that died uncleanly leaves its walk-away alert behind, and
        // swiping it away counts as Dismiss. That must not bring the service
        // back, whether or not the rider stopped scanning; it only clears the
        // alert, and leaves every other notification where it is. The marker is
        // what an unclean death leaves set.
        Prefs(app).serviceRunningMarker = true
        nm.notify(OTHER_ID, Notification.Builder(app, "other").setSmallIcon(android.R.drawable.ic_dialog_info).build())
        for (scanning in listOf(true, false)) {
            Prefs(app).serviceEnabled = scanning
            for (action in listOf(InternalControlReceiver.ACTION_WALKAWAY_DISMISS, InternalControlReceiver.ACTION_WALKAWAY_SNOOZE)) {
                ServiceNotifications(app) { Prefs(app) }.apply { ensureChannels() }.postWalkAway(WalkAwayAlarmKind.DASHCAM)
                assertEquals(2, shadowOf(nm).allNotifications.size)
                receiver.onReceive(app, Intent(action))
                assertNull("$action started a service (scanning=$scanning)", shadowOf(app).peekNextStartedService())
                assertEquals("$action must clear the alert (scanning=$scanning)", 1, shadowOf(nm).allNotifications.size)
                assertNotNull("$action cleared another notification", shadowOf(nm).getNotification(OTHER_ID))
            }
        }
        // Each clear is on the record; with no volume left at maximum, no repair is.
        val lines = journal()
        assertEquals(4, lines.count { it.endsWith("walk-away alarm cleared by rider; no service running") })
        assertTrue(lines.none { "volume restored" in it })
    }

    @Test
    fun aRunningServiceAlwaysGetsTheWalkAwayButtons() {
        // Whatever Stop scanning says, a running service may be sounding the
        // alarm, and the buttons are the way to silence it. Its alarm owns the
        // saved volume then, so the receiver must not restore it under it.
        val am = app.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        val prefs = Prefs(app).apply {
            serviceEnabled = false
            walkAwaySavedAlarmVolume = 2
        }
        serviceRunning()
        receiver.onReceive(app, Intent(InternalControlReceiver.ACTION_WALKAWAY_DISMISS))
        val dismissed = shadowOf(app).nextStartedService
        assertEquals(BikeRadarService.ACTION_WALKAWAY_DISMISS, dismissed?.action)
        assertEquals(BikeRadarService::class.java.name, dismissed?.component?.className)
        receiver.onReceive(app, Intent(InternalControlReceiver.ACTION_WALKAWAY_SNOOZE))
        assertEquals(BikeRadarService.ACTION_WALKAWAY_SNOOZE, shadowOf(app).nextStartedService?.action)
        assertEquals("a live alarm's volume was restored under it", max, am.getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals(2, prefs.walkAwaySavedAlarmVolume)
    }

    @Test
    fun anAlertClearedWithNoServiceRunningRestoresTheAlarmVolumeItLeftAtMaximum() {
        // The service repairs this when it starts; with none running the
        // receiver does it.
        val am = app.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        val prefs = Prefs(app).apply { walkAwaySavedAlarmVolume = 2 }
        receiver.onReceive(app, Intent(InternalControlReceiver.ACTION_WALKAWAY_DISMISS))
        assertEquals(2, am.getStreamVolume(AudioManager.STREAM_ALARM))
        assertNull(prefs.walkAwaySavedAlarmVolume)
        assertTrue(journal().first().endsWith("walk-away alarm volume restored; the service stopped mid-alarm"))
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

    private companion object {
        const val OTHER_ID = 4242
    }
}
