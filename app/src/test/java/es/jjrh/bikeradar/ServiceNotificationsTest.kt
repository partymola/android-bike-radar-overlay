// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Channel lifecycle + the foreground notification's paused/active branch.
 * The smoke test only asserts "some channel exists"; this pins the specific
 * channels, the legacy-channel migration, and the notification text.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceNotificationsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(Application.NOTIFICATION_SERVICE) as NotificationManager

    private fun notifications(prefs: Prefs = Prefs(app)) = ServiceNotifications(app) { prefs }

    @Test fun ensureChannelsCreatesTheThreeServiceChannels() {
        notifications().ensureChannels()
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.CHANNEL_ID))
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.LIGHT_FAIL_CHANNEL_ID))
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.WALKAWAY_CHANNEL_ID))
    }

    @Test fun ensureChannelsDeletesSupersededWalkAwayChannel() {
        // A v1 install left a channel under the old id; the migration must drop
        // it so the v3 channel's properties take effect.
        nm.createNotificationChannel(
            android.app.NotificationChannel("bike_radar_walkaway", "old", NotificationManager.IMPORTANCE_HIGH),
        )
        notifications().ensureChannels()
        assertNull("legacy walk-away channel must be deleted", nm.getNotificationChannel("bike_radar_walkaway"))
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.WALKAWAY_CHANNEL_ID))
    }

    @Test fun ensureChannelsIsIdempotent() {
        val n = notifications()
        n.ensureChannels()
        n.ensureChannels()
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.CHANNEL_ID))
    }

    @Test fun foregroundTextIsActiveWhenNotPaused() {
        val prefs = Prefs(app).apply { pausedUntilEpochMs = 0L }
        val notif = notifications(prefs).buildForeground()
        assertEquals(
            app.getString(R.string.svc_main_notif_active),
            notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test fun foregroundTextSwitchesToPausedWhenPaused() {
        val prefs = Prefs(app).apply { pausedUntilEpochMs = System.currentTimeMillis() + 3_600_000L }
        val text = notifications(prefs).buildForeground()
            .extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        // Paused branch differs from the active string - proves the toggle flips
        // rather than always rendering one state.
        assertNotNull(text)
        assert(text != app.getString(R.string.svc_main_notif_active)) {
            "paused notification must not show the active text"
        }
    }
}
