// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The looping alarm sound, abstracted behind an interface so the [WalkAwayAlarm]
 * orchestration (focus, alarm-volume save/force/restore, play-failure rollback,
 * the self-capping job) can be unit-tested without a real [Ringtone] - Robolectric
 * ships no Ringtone shadow, so `isPlaying` never flips and `play()` can't be made
 * to fail under test. Production uses [systemAlarmTone]; tests inject a fake.
 */
internal interface AlarmTone {
    val isPlaying: Boolean

    fun play()

    fun stop()
}

/**
 * The walk-away ("forgotten dashcam") alarm tone: a looping alarm-stream
 * ringtone plus an explicit vibration pattern, raised by the walk-away decider
 * when the rider leaves a still-streaming radar/dashcam behind on the bike.
 *
 * Off the radar-decode hot path, but it IS the safety dismount alarm, so it
 * deliberately overrides the rider's quiet-hours posture:
 *  - it plays under `USAGE_ALARM` so it routes through the alarm stream and
 *    follows the alarm-volume policy regardless of DND, rather than the
 *    notification channel (which modern Pixel/Android normalises to
 *    `USAGE_NOTIFICATION` and DND silences even with `bypassDnd = true`);
 *  - it holds `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` with an empty focus-change
 *    listener, so it never ducks or pauses for transient focus loss - the whole
 *    point is to keep alerting until the rider acts;
 *  - it forces `STREAM_ALARM` to max for the duration and restores the saved
 *    level in [stop], so a low bedside-alarm slider can't swallow a
 *    forgotten-dashcam alert;
 *  - it vibrates explicitly via the `Vibrator` service, because channel-level
 *    vibration is DND-suppressed when the channel doesn't bypass DND.
 *
 * [start] and [stop] are `@Synchronized` on this instance so the FIRE path, the
 * dismiss / snooze / auto-dismiss handlers, the self-capping job, and onDestroy
 * teardown can't interleave the ringtone / focus / saved-volume state. The tone
 * self-caps after [WALKAWAY_RINGTONE_CAP_MS]; the decider's rate limit then
 * re-fires the notification for another bounded window if the rider still hasn't
 * acted.
 *
 * The orchestration - focus request, alarm-volume save / force-max / restore,
 * the play-failure rollback, and the self-capping job - is unit-tested by
 * injecting a fake [AlarmTone] (see WalkAwayAlarmTest). The real [Ringtone]
 * playback and the actual audibility are exercised on-bike, as with
 * [AlertBeeper].
 */
internal class WalkAwayAlarm(
    private val context: Context,
    private val scope: CoroutineScope,
    private val toneFactory: (AudioAttributes) -> AlarmTone = { attrs -> systemAlarmTone(context, attrs) },
) {
    /** Looping alarm-stream tone played alongside the walk-away
     *  notification; null when not playing. The notification channel's
     *  audio attributes are normalised to USAGE_NOTIFICATION on modern
     *  Pixel/Android, which DND silences even with mBypassDnd=true.
     *  We play the alarm tone explicitly with USAGE_ALARM so it routes
     *  through the alarm stream and follows the user's alarm-volume
     *  policy regardless of DND state. */
    @Volatile private var tone: AlarmTone? = null

    /** Audio-focus token held while [tone] is playing. Released in [stop]. */
    @Volatile private var audioFocusRequest: AudioFocusRequest? = null

    /** Pre-alert STREAM_ALARM volume, captured before the walk-away tone
     *  forces the alarm stream to max so the rider can't sleep through a
     *  forgotten-dashcam alert just because their alarm slider was low.
     *  Restored in [stop]. Null when no override is in effect. */
    @Volatile private var savedAlarmVolume: Int? = null

    /** Single-slot job that stops the alarm tone after
     *  [WALKAWAY_RINGTONE_CAP_MS]. Without this cap a forgotten alert
     *  would loop forever; the decider's rate limit then re-fires the
     *  notification, re-starting the tone for another bounded window. */
    private var ringtoneCapJob: Job? = null

    @Synchronized
    fun start() {
        vibrate()
        if (tone?.isPlaying == true) return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            // Empty listener is intentional: a walk-away alarm should
            // not duck or pause for transient focus loss. The whole
            // point is to keep alerting until the rider acts.
            .setOnAudioFocusChangeListener { }
            .build()
        if (am.requestAudioFocus(focusReq) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "walk-away audio focus denied; playing without focus")
        }

        val newTone = try {
            toneFactory(attrs)
        } catch (t: Throwable) {
            Log.w(TAG, "ringtone setup failed: $t")
            try {
                am.abandonAudioFocusRequest(focusReq)
            } catch (_: Throwable) {}
            return
        }
        // Force the alarm stream to max for the duration of the alert.
        // Mirrors the alarm-clock pattern: a rider whose phone alarm
        // volume is set low for sleep shouldn't lose a £200 dashcam to
        // their bedside-tone preference. Saved level is restored in
        // stop(). Best-effort; some OEMs reject volume writes from
        // background services and that's fine.
        val saved = try {
            am.getStreamVolume(AudioManager.STREAM_ALARM)
        } catch (_: Throwable) {
            null
        }
        if (saved != null) {
            try {
                am.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "alarm-stream max-out failed: $t")
            }
        }

        try {
            newTone.play()
        } catch (t: Throwable) {
            Log.w(TAG, "ringtone play failed: $t")
            try {
                am.abandonAudioFocusRequest(focusReq)
            } catch (_: Throwable) {}
            // Roll back the alarm-stream override so a play() failure
            // doesn't leave the rider's morning alarm stuck at max.
            if (saved != null) {
                try {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
                } catch (_: Throwable) {}
            }
            return
        }

        // Commit the new state only after play() succeeds, so a partial
        // setup never leaks a focus token or a half-initialised tone.
        audioFocusRequest = focusReq
        tone = newTone
        savedAlarmVolume = saved
        ringtoneCapJob?.cancel()
        ringtoneCapJob = scope.launch {
            delay(WALKAWAY_RINGTONE_CAP_MS)
            stop()
        }
        Log.i(TAG, "walk-away alarm tone started (cap=${WALKAWAY_RINGTONE_CAP_MS / 1000}s)")
    }

    /**
     * Stop the alarm tone and release audio focus. Safe to call
     * unconditionally - all paths null-check.
     */
    @Synchronized
    fun stop() {
        ringtoneCapJob?.cancel()
        ringtoneCapJob = null
        tone?.let {
            try {
                it.stop()
            } catch (_: Throwable) {}
        }
        tone = null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusRequest?.let { req ->
            try {
                am.abandonAudioFocusRequest(req)
            } catch (_: Throwable) {}
        }
        audioFocusRequest = null
        savedAlarmVolume?.let { saved ->
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "alarm-stream restore failed: $t")
            }
        }
        savedAlarmVolume = null
    }

    /**
     * Vibrate the walk-away pattern explicitly via the Vibrator service.
     * Channel-level vibration is suppressed by DND when the channel doesn't
     * bypass DND; the explicit `USAGE_ALARM` Vibrator call in [DndVibration] is
     * not.
     */
    private fun vibrate() = DndVibration.vibrate(context, ServiceNotifications.WALKAWAY_VIBRATE_PATTERN)

    companion object {
        private const val TAG = "BikeRadar"

        /** Hard cap on a single alarm-tone episode. Without this cap a
         *  forgotten alert would loop forever; the decider's rate limit then
         *  re-fires the notification, re-starting the tone for another bounded
         *  window. */
        private const val WALKAWAY_RINGTONE_CAP_MS = 60_000L

        /**
         * The production [AlarmTone]: the system default alarm sound, looped
         * under the given [attrs]. A bundled sound asset was dropped because its
         * licence was not GPL-compatible; the device alarm keeps the app
         * asset-free. It does share the rider's wake-up tone, but the alarm
         * stream is forced to max while it plays and the cue loops under
         * USAGE_ALARM, so it still cuts through. Falls back through the
         * ringtone / notification default if no default alarm is set. A
         * null/failed lookup throws and is rolled back by [start]'s setup catch.
         */
        private fun systemAlarmTone(context: Context, attrs: AudioAttributes): AlarmTone {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, uri).apply {
                audioAttributes = attrs
                isLooping = true
            }
            return object : AlarmTone {
                override val isPlaying: Boolean get() = ringtone.isPlaying
                override fun play() = ringtone.play()
                override fun stop() = ringtone.stop()
            }
        }
    }
}
