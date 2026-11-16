// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

/**
 * What both light-failure alerts, the tail light's and the dashcam light's,
 * wait on and play. Neither tone is one of [AlertBeeper]'s cues, so the states
 * in which the audio steps back do not apply to either on their own.
 *
 * True while no cue of the ride is sounding ([cueSounding]), no pause holds and
 * no call is on, so an alert's alerting post and tone never start while one
 * does; a cue starting during the tone is not waited for. A pause ends at its
 * own deadline or on a resume, and a released beeper never reads as quiet
 * (`RadarLinkControllerHarnessTest.theFailToneWaitsForACueToEnd`,
 * `CameraLightLinkControllerHarnessTest.theFailToneWaitsForAResume`,
 * `theFailToneWaitsForAPauseToRunOut`, `theFailToneWaitsForACallToEnd`,
 * `aHeldFailToneNeverSoundsOnceTheBeeperIsReleased`).
 */
internal fun lightFailToneQuiet(
    context: Context,
    prefs: Prefs,
    wallClock: () -> Long,
    cueSounding: Flow<Boolean>,
): Flow<Boolean> {
    val paused = channelFlow {
        prefs.flow.collectLatest { s ->
            val left = s.pausedUntilEpochMs - wallClock()
            send(left > 0)
            if (left > 0) {
                delay(left)
                send(false)
            }
        }
    }
    val audio = context.getSystemService(AudioManager::class.java)
    return combine(cueSounding, paused, AlertBeeper.callInProgress(audio, context.mainExecutor)) { cue, pause, call ->
        !cue && !pause && !call
    }
}

/** The failure tone, on the notification stream. A cancel while it plays ends
 *  the caller too (`LightFailAudioTest.aCancelDuringTheToneEndsTheCallerToo`).
 *  [light] names the light in the log line when the tone cannot play. */
internal suspend fun playLightFailTone(tag: String, light: String) {
    var tg: ToneGenerator? = null
    try {
        tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tg.startTone(ToneGenerator.TONE_PROP_NACK, 400)
        delay(600)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "$light failure tone could not play: $e")
    } finally {
        tg?.release()
    }
}
