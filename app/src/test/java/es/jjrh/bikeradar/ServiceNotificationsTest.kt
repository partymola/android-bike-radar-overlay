// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.VibrationAttributes
import android.os.VibratorManager
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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

    @Test fun ensureChannelsCreatesAllServiceChannels() {
        notifications().ensureChannels()
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.CHANNEL_ID))
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.LIGHT_FAIL_CHANNEL_ID))
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.WALKAWAY_CHANNEL_ID))
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.FORGOT_LOCK_CHANNEL_ID))
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.RIDE_SUMMARY_CHANNEL_ID))
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

    @Test fun ensureChannelsDeletesSupersededForgotLockChannel() {
        // A pre-v2 install left the forgot-lock channel under the old id; the
        // migration must drop it so the v2 properties (channel vibration off,
        // bypass-DND) take effect on the new id.
        nm.createNotificationChannel(
            android.app.NotificationChannel("bike_radar_forgot_lock", "old", NotificationManager.IMPORTANCE_HIGH),
        )
        notifications().ensureChannels()
        assertNull("legacy forgot-lock channel must be deleted", nm.getNotificationChannel("bike_radar_forgot_lock"))
        val v2 = nm.getNotificationChannel(ServiceNotifications.FORGOT_LOCK_CHANNEL_ID)
        assertNotNull(v2)
        // v2 disables channel vibration so the explicit DndVibration buzz is the
        // only one - a regression to channel vibration would double-buzz off DND.
        assertFalse("v2 forgot-lock channel must disable channel vibration", v2.shouldVibrate())
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

    private fun title(sbn: android.service.notification.StatusBarNotification) = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    @Test fun bondLostPostsToMinChannelThenCancels() {
        val n = notifications()
        n.postBondLost()
        val sbn = nm.activeNotifications.single()
        assertEquals(ServiceNotifications.CHANNEL_ID, sbn.notification.channelId)
        assertEquals(
            app.getString(R.string.svc_main_bond_lost_text),
            sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        n.cancelBondLost()
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test fun walkAwayPostsWithDismissAndSnoozeActionsThenCancels() {
        val n = notifications()
        n.postWalkAway()
        val sbn = nm.activeNotifications.single()
        assertEquals(ServiceNotifications.WALKAWAY_CHANNEL_ID, sbn.notification.channelId)
        assertEquals(app.getString(R.string.svc_main_walkaway_notif_title), title(sbn))
        // Dismiss + Snooze: both actions must be present for the rider to act.
        assertEquals(2, sbn.notification.actions.size)
        n.cancelWalkAway()
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test fun forgotToLockPostsToForgotLockChannelAndVibrates() {
        // The reminder must post on the forgot-lock channel AND drive an explicit
        // vibration: the v2 channel disables channel vibration so the buzz comes
        // from DndVibration (USAGE_ALARM) and pierces Do Not Disturb.
        val shadowVibrator = shadowOf(app.getSystemService(VibratorManager::class.java).defaultVibrator)
        shadowVibrator.setHasVibrator(true)
        val n = notifications()
        n.postForgotToLock()
        val sbn = nm.activeNotifications.single()
        assertEquals(ServiceNotifications.FORGOT_LOCK_CHANNEL_ID, sbn.notification.channelId)
        assertEquals(app.getString(R.string.svc_main_forgot_lock_title), title(sbn))
        assertTrue("forgot-to-lock must drive an explicit DND-piercing vibration", shadowVibrator.isVibrating)
        // The DND-piercing property IS the USAGE_ALARM attribute (channel
        // vibration is silenced under DND; a USAGE_ALARM vibration is not). Pin
        // it so a regression to USAGE_NOTIFICATION can't silently re-break it.
        assertEquals(
            VibrationAttributes.USAGE_ALARM,
            (shadowVibrator.vibrationAttributesFromLastVibration as VibrationAttributes).usage,
        )
        n.cancelForgotToLock()
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test fun lightFailAndRadarLightFailUseDistinctIdsAndTitles() {
        // Distinct ids are load-bearing: a dashcam-light failure and a
        // radar-light failure must coexist, not clobber each other.
        val n = notifications()
        n.postLightFail("Day Flash")
        n.postRadarLightFail("Solid")
        assertEquals(2, nm.activeNotifications.size)
        assertEquals(
            setOf(
                app.getString(R.string.svc_main_dashcam_light_title),
                app.getString(R.string.svc_main_radar_light_title),
            ),
            nm.activeNotifications.map { title(it) }.toSet(),
        )
    }
}
