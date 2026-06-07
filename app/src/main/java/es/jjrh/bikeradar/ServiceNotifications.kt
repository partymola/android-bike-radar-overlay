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
 * The feature alerts that POST to the walk-away and light-fail channels
 * (walk-away alarm, bond-lost, light-switch-failed) still live in the service
 * for now and reference the channel IDs here; they move to their own
 * coordinators in later steps of the service split. This class therefore
 * defines every channel the service uses, but only builds the foreground
 * notification itself.
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
        private const val NOTIF_ACTION_REQ = 0xB1CD
        private const val BOND_NOTIF_REQ = 0xB1CE
    }
}
