// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

/**
 * One-shot vibration that fires through Do Not Disturb.
 *
 * Channel-level notification vibration is suppressed under DND when the channel
 * does not bypass it (and modern Pixel/Android normalises channel audio to
 * USAGE_NOTIFICATION, which DND silences even with `bypassDnd = true`). An
 * explicit [android.os.Vibrator] call under `USAGE_ALARM` is NOT suppressed, so
 * this is the mechanism the safety cues use to reach the rider regardless of
 * their quiet-hours posture: the walk-away alarm ([WalkAwayAlarm]) and the
 * forgot-to-lock reminder ([ServiceNotifications.postForgotToLock]).
 *
 * Best-effort: no vibrator, a revoked service, or an OEM that rejects the call
 * is swallowed - the vibration is one channel among several, never the only one.
 */
internal object DndVibration {
    private const val TAG = "BikeRadar"

    fun vibrate(context: Context, pattern: LongArray) {
        // minSdk 31, so VibratorManager is always present.
        val vibrator = (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
        if (vibrator == null || !vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(pattern, -1)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ALARM)
                        .build(),
                )
            } else {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, attrs)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DND vibrate failed: $t")
        }
    }
}
