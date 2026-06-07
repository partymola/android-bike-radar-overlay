// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import es.jjrh.bikeradar.data.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notification-channel lifecycle and the persistent foreground-service
 * notification. Owns the channel identities (so a single place defines them,
 * including the immutable-channel migration that drops superseded walk-away
 * IDs) and the ongoing "active / paused until HH:mm" notification with its
 * pause/resume action.
 *
 * It also builds the feature notifications whose channels it owns: the
 * walk-away alarm ([postWalkAway]), the bond-lost alert ([postBondLost]), and
 * the light-switch-failed alerts ([postLightFail] / [postRadarLightFail]). The
 * service still drives the accompanying alarm tone / NACK beep; this class owns
 * every channel and builds every notification the service posts.
 */
internal class ServiceNotifications(
    private val context: Context,
    private val prefs: () -> Prefs,
) {
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels() {
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.svc_main_channel_name), NotificationManager.IMPORTANCE_MIN),
            )
        }
        // Drop legacy walk-away channels. Channel properties (sound,
        // vibration pattern, importance) are immutable post-creation,
        // so any code change has to migrate to a fresh ID and delete
        // the old one. The user's per-channel preferences (e.g. Override
        // Do Not Disturb) reset on this migration; the dashcam settings
        // row deeplinks back to the new channel for re-grant.
        WALKAWAY_CHANNEL_IDS_LEGACY.forEach { id ->
            if (nm.getNotificationChannel(id) != null) {
                nm.deleteNotificationChannel(id)
            }
        }
        if (nm.getNotificationChannel(LIGHT_FAIL_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    LIGHT_FAIL_CHANNEL_ID,
                    context.getString(R.string.svc_main_light_fail_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.svc_main_light_fail_channel_desc)
                    enableVibration(true)
                    vibrationPattern = LIGHT_FAIL_VIBRATE_PATTERN
                },
            )
        }

        if (nm.getNotificationChannel(WALKAWAY_CHANNEL_ID) == null) {
            // HIGH importance, no sound and no vibration on the channel.
            // Both modalities are driven explicitly from the FIRE path:
            // - audio: Ringtone with USAGE_ALARM (channel sound is
            //   normalised to USAGE_NOTIFICATION, which DND silences).
            // - haptics: Vibrator service (channel vibration is
            //   suppressed under DND when canBypassDnd is false, and
            //   the migration to v3 resets the user's bypass grant).
            // Driving both explicitly means the alarm fires through DND
            // regardless of the user's per-channel preferences.
            val ch = NotificationChannel(
                WALKAWAY_CHANNEL_ID,
                context.getString(R.string.svc_main_walkaway_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.svc_main_walkaway_channel_desc)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }
    }

    fun buildForeground(): Notification {
        val paused = prefs().isPaused
        val contentText = if (paused) {
            val t = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(prefs().pausedUntilEpochMs))
            context.getString(R.string.svc_main_notif_paused_until, t)
        } else {
            context.getString(R.string.svc_main_notif_active)
        }
        val actionLabel = if (paused) context.getString(R.string.svc_main_notif_action_resume) else context.getString(R.string.svc_main_notif_action_pause)
        val actionBroadcast = if (paused) InternalControlReceiver.ACTION_RESUME else InternalControlReceiver.ACTION_PAUSE_1H
        val piFlags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val actionPi = PendingIntent.getBroadcast(
            context,
            NOTIF_ACTION_REQ,
            Intent(context, InternalControlReceiver::class.java).apply { action = actionBroadcast },
            piFlags,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.svc_main_notif_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, actionLabel, actionPi)
            .build()
    }

    /** Re-post the foreground notification in place (pause/resume toggle,
     *  pause-expiry). The initial post is the service's startForeground. */
    fun postForeground() = nm.notify(NOTIF_ID, buildForeground())

    /** Bond-lost alert: the radar's bond was removed in system settings, so the
     *  reconnect loop was stopped. Deep-links to Bluetooth settings so the rider
     *  can re-pair, and explains why the link went silent. Posted from the
     *  service's bond-lost path. */
    fun postBondLost() {
        ensureChannels()
        val piFlags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val openSettings = PendingIntent.getActivity(
            context,
            BOND_NOTIF_REQ,
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            piFlags,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.svc_main_notif_title))
            .setContentText(context.getString(R.string.svc_main_bond_lost_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openSettings)
            .build()
        nm.notify(NOTIF_BOND_LOST_ID, notif)
    }

    /** Clear the bond-lost notification (service teardown). */
    fun cancelBondLost() = nm.cancel(NOTIF_BOND_LOST_ID)

    /** Walk-away alarm notification ("you left the dashcam on the bike"): a
     *  HIGH-priority reminder on the walk-away channel with Dismiss + Snooze
     *  actions (tapping or swiping the body counts as Dismiss). The audible +
     *  haptic alarm tone is driven separately by the service. */
    fun postWalkAway() {
        val piFlags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val dismissPi = PendingIntent.getBroadcast(
            context,
            NOTIF_WALKAWAY_DISMISS_REQ,
            Intent(context, InternalControlReceiver::class.java).apply {
                action = InternalControlReceiver.ACTION_WALKAWAY_DISMISS
            },
            piFlags,
        )
        val snoozePi = PendingIntent.getBroadcast(
            context,
            NOTIF_WALKAWAY_SNOOZE_REQ,
            Intent(context, InternalControlReceiver::class.java).apply {
                action = InternalControlReceiver.ACTION_WALKAWAY_SNOOZE
            },
            piFlags,
        )
        val notif = NotificationCompat.Builder(context, WALKAWAY_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.svc_main_walkaway_notif_title))
            .setContentText(context.getString(R.string.svc_main_walkaway_notif_text))
            .setSmallIcon(R.drawable.ic_videocam_off)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVibrate(WALKAWAY_VIBRATE_PATTERN)
            .addAction(0, context.getString(R.string.svc_main_walkaway_action_dismiss), dismissPi)
            .addAction(0, context.getString(R.string.svc_main_walkaway_action_snooze), snoozePi)
            // Tapping the body or swipe-dismissing both mark the episode handled.
            .setContentIntent(dismissPi)
            .setDeleteIntent(dismissPi)
            .build()
        nm.notify(NOTIF_WALKAWAY_ID, notif)
    }

    /** Clear the walk-away alarm notification. */
    fun cancelWalkAway() = nm.cancel(NOTIF_WALKAWAY_ID)

    /** Front camera/dashcam light-mode-switch-failed alert: the BLE write to set
     *  the mode was not ACKed. [modeName] is the localized failed-mode label.
     *  The NACK beep is played separately by the service. */
    fun postLightFail(modeName: String) = postLightFail(
        R.string.svc_main_dashcam_light_title,
        modeName,
        NOTIF_LIGHT_FAIL_ID,
    )

    /** Rear radar tail-light variant, with a distinct id so it and the dashcam
     *  light-fail notification never clobber each other. */
    fun postRadarLightFail(modeName: String) = postLightFail(
        R.string.svc_main_radar_light_title,
        modeName,
        NOTIF_RADAR_LIGHT_FAIL_ID,
    )

    private fun postLightFail(titleRes: Int, modeName: String, notifId: Int) {
        ensureChannels()
        val notif = NotificationCompat.Builder(context, LIGHT_FAIL_CHANNEL_ID)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(R.string.svc_main_light_fail_text, modeName))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setVibrate(LIGHT_FAIL_VIBRATE_PATTERN)
            .build()
        nm.notify(notifId, notif)
    }

    companion object {
        const val CHANNEL_ID = "bike_radar_min"

        // v2 channel created with alarm-stream sound; the v1 legacy id
        // is deleted on channel-ensure so an upgrade picks up sound.
        const val WALKAWAY_CHANNEL_ID = "bike_radar_walkaway_v3"
        const val LIGHT_FAIL_CHANNEL_ID = "bike_radar_light_fail"
        private val WALKAWAY_CHANNEL_IDS_LEGACY = listOf(
            "bike_radar_walkaway",
            "bike_radar_walkaway_v2",
        )
        val LIGHT_FAIL_VIBRATE_PATTERN = longArrayOf(0, 300, 150, 300)

        const val NOTIF_ID = 1
        private const val NOTIF_BOND_LOST_ID = 2
        private const val NOTIF_WALKAWAY_ID = 3
        private const val NOTIF_LIGHT_FAIL_ID = 4
        private const val NOTIF_RADAR_LIGHT_FAIL_ID = 5
        private const val NOTIF_ACTION_REQ = 0xB1CD
        private const val BOND_NOTIF_REQ = 0xB1CE
        private const val NOTIF_WALKAWAY_DISMISS_REQ = 0xB1CF
        private const val NOTIF_WALKAWAY_SNOOZE_REQ = 0xB1D0

        // Pixel native alarm cadence: three 1.5 s pulses with 0.8 s gaps, ~7 s
        // total - long enough to feel through fabric and recognisable as an
        // alarm, not a routine notification. Shared with the service's walk-away
        // alarm-tone path, which fires haptics explicitly through the Vibrator.
        val WALKAWAY_VIBRATE_PATTERN = longArrayOf(0, 1500, 800, 1500, 800, 1500)
    }
}
