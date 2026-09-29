// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import es.jjrh.bikeradar.AlertBeeper
import es.jjrh.bikeradar.AlertCue
import es.jjrh.bikeradar.CuePlayer
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.RadarOverlayView
import es.jjrh.bikeradar.RadarState
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.SoundDemo
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.playCue
import es.jjrh.bikeradar.radarStreamIsLive
import kotlinx.coroutines.delay
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The caption under the scene: what the sound just heard means. */
@StringRes
internal fun soundDemoCaption(lastCue: AlertCue?): Int = when (lastCue) {
    null, AlertCue.Silence -> R.string.sound_demo_caption_nothing
    is AlertCue.Beep -> when (lastCue.count) {
        1 -> R.string.sound_demo_caption_one
        2 -> R.string.sound_demo_caption_two
        else -> R.string.sound_demo_caption_three
    }
    AlertCue.Clear -> R.string.sound_demo_caption_clear
    AlertCue.Urgent -> R.string.sound_demo_caption_urgent
}

/**
 * Builds its beeper at the first sound rather than when the screen opens, so a
 * screen nobody plays touches no volume and holds no audio tracks.
 */
internal class DemoCuePlayer(private val create: () -> AlertBeeper?) : CuePlayer {
    private var built = false
    private var beeper: AlertBeeper? = null

    private fun beeper(): AlertBeeper? {
        if (!built) {
            built = true
            beeper = create()
        }
        return beeper
    }

    override fun play(beeps: Int) {
        beeper()?.play(beeps)
    }
    override fun playClear() {
        beeper()?.playClear()
    }
    override fun playUrgent() {
        beeper()?.playUrgent()
    }
    override fun playRadarDropped() {
        beeper()?.playRadarDropped()
    }
    override fun playRadarReconnected() {
        beeper()?.playRadarReconnected()
    }

    /** Applies to a beeper already built; one built later reads the saved volume. */
    fun setVolumePct(pct: Int) {
        beeper?.setVolumePct(pct)
    }

    fun release() {
        built = true
        beeper?.release()
    }
}

/**
 * The player the demo and the glossary sound through, released when the screen
 * goes. A second beeper on the alarm stream the ride's beeper also lifts, so it
 * joins both interlocks through the shared slots
 * (`WalkAwayAlarmBeeperInterlockTest.aSoundDemoLiftIsInterlockedWithTheWalkAwayAlarmToo`).
 */
@Composable
internal fun rememberDemoCuePlayer(
    prefs: Prefs,
    create: (Context) -> AlertBeeper? = { newDemoBeeper(it, prefs) },
): DemoCuePlayer {
    val context = LocalContext.current
    val player = remember { DemoCuePlayer { create(context) } }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

internal fun newDemoBeeper(
    context: Context,
    prefs: Prefs,
    executor: Executor = Executors.newSingleThreadExecutor(),
): AlertBeeper? = try {
    AlertBeeper(
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
        executor = executor,
        saveAlarmFloor = { prefs.alertBeeperSavedAlarmVolume = it },
        // No crash repair from a screen: the ride's beeper may hold a lift from
        // this slot right now. The service repairs a leak when it next starts.
        loadAlarmFloor = { null },
        sharedFloorBaseline = { prefs.alertBeeperSavedAlarmVolume },
        walkAwayOverrideActive = { prefs.walkAwaySavedAlarmVolume != null },
    ).also { it.setVolumePct(prefs.alertVolume) }
} catch (t: Throwable) {
    Log.w("BikeRadar", "demo beeper unavailable", t)
    (executor as? ExecutorService)?.shutdown()
    null
}

/**
 * Whether the radar is streaming, ticked so it turns false again once frames
 * stop. Sample sounds wait while it is true: the ride's own alerts sound then.
 */
@Composable
internal fun rememberRadarStreaming(): Boolean {
    val radarState by RadarStateBus.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(5_000)
            }
        }
    }
    return radarStreamIsLive(radarState, nowMs)
}

/**
 * While shown, the volume buttons move whichever volume sets how loud the alerts
 * are ([AlertBeeper.cueLoudnessStream]), re-read as the rider changes either.
 * Set on every tick, and handed back to the default rather than to whatever was
 * there before: in a navigation transition the two screens' effects overlap, so
 * "before" can be the other screen's setting.
 */
@Composable
internal fun VolumeKeysFollowCues(prefs: Prefs) {
    val activity = LocalActivity.current ?: return
    val audio = remember { activity.getSystemService(AudioManager::class.java) }
    DisposableEffect(activity) {
        onDispose { activity.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                activity.volumeControlStream = audio?.let { AlertBeeper.cueLoudnessStream(it, prefs.alertBeeperSavedAlarmVolume) }
                    ?: AudioManager.STREAM_ALARM
                delay(250)
            }
        }
    }
}

/**
 * The demo as a whole screen: first in onboarding, and again from Settings.
 * [mark] is the label over the title, which differs between the two.
 */
@Composable
fun SoundDemoScreen(prefs: Prefs, @StringRes mark: Int, onDone: () -> Unit) {
    UiTheme {
        VolumeKeysFollowCues(prefs)
        val br = LocalBrColors.current
        Column(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
            SoundDemoStep(
                player = rememberDemoCuePlayer(prefs),
                canPlay = !rememberRadarStreaming(),
                onContinue = onDone,
                mark = mark,
            )
        }
    }
}

/**
 * The sound demo: tap Play, watch a scripted example ride with its real sounds,
 * then Continue. Nothing plays until the rider taps, since alerts sound on the
 * alarm stream and ignore a muted media volume.
 */
@Composable
internal fun SoundDemoStep(
    player: CuePlayer?,
    canPlay: Boolean,
    onContinue: () -> Unit,
    @StringRes mark: Int = R.string.sound_demo_mark,
) {
    var runs by rememberSaveable { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var tMs by remember { mutableLongStateOf(0L) }
    var lastCue by remember { mutableStateOf<AlertCue?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) playing = false }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The screen going off stops the scene, and a 15 s timeout would cut it
    // before the urgent warning.
    val view = LocalView.current
    DisposableEffect(view, playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(playing, runs, canPlay) {
        if (!playing) return@LaunchedEffect
        if (!canPlay) {
            playing = false
            return@LaunchedEffect
        }
        val playback = SoundDemo.Playback { cue -> player?.playCue(cue) }
        val start = withFrameMillis { it }
        while (true) {
            val elapsed = withFrameMillis { it } - start
            playback.advanceTo(elapsed)
            lastCue = playback.lastCue
            tMs = elapsed.coerceAtMost(SoundDemo.DURATION_MS)
            if (elapsed >= SoundDemo.DURATION_MS) break
        }
        playing = false
    }

    SoundDemoStepContent(
        tMs = tMs,
        lastCue = lastCue,
        playing = playing,
        played = runs > 0,
        canPlay = canPlay,
        onPlay = {
            runs += 1
            playing = true
        },
        onContinue = {
            playing = false
            onContinue()
        },
        mark = mark,
    )
}

/** Stateless leaf so the goldens can render any moment of the scene. */
@Composable
internal fun SoundDemoStepContent(
    tMs: Long,
    lastCue: AlertCue?,
    playing: Boolean,
    played: Boolean,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onContinue: () -> Unit,
    @StringRes mark: Int = R.string.sound_demo_mark,
) {
    val br = LocalBrColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            StepHeroBlock(
                icon = Icons.AutoMirrored.Filled.DirectionsBike,
                tint = br.brand,
                mark = stringResource(mark),
                title = stringResource(R.string.sound_demo_title),
                sub = stringResource(R.string.sound_demo_sub),
            )
            // No caption while locked: a live radar is streaming then, and "the
            // radar sees nothing" would read as its status.
            SoundDemoScene(tMs = tMs, lastCue = lastCue, captioned = canPlay)
            if (!canPlay) {
                Text(
                    text = stringResource(R.string.sound_demo_radar_streaming),
                    color = br.fgDim,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        // Once the scene has been watched, moving on is the main action.
        if (played && !playing && canPlay) {
            FooterCtaDual(
                primary = stringResource(R.string.common_continue),
                secondary = stringResource(R.string.sound_demo_play_again),
                primaryEnabled = true,
                onPrimary = onContinue,
                onSecondary = onPlay,
            )
        } else {
            FooterCtaDual(
                primary = stringResource(if (playing) R.string.sound_demo_playing else R.string.sound_demo_play),
                secondary = stringResource(R.string.common_continue),
                primaryEnabled = canPlay && !playing,
                onPrimary = onPlay,
                onSecondary = onContinue,
            )
        }
    }
}

/** The overlay strip as a ride draws it, beside the caption for the last sound. */
@Composable
internal fun SoundDemoScene(tMs: Long, lastCue: AlertCue?, captioned: Boolean) {
    val br = LocalBrColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(br.bgElev1)
                .border(1.dp, br.hairline, RoundedCornerShape(12.dp)),
        ) {
            AndroidView(
                factory = { context ->
                    RadarOverlayView(context).apply {
                        setVisualMaxM(SoundDemo.VISUAL_MAX_M)
                        setAlertMaxM(SoundDemo.ALERT_MAX_M)
                    }
                },
                update = { view ->
                    view.setState(
                        RadarState(
                            vehicles = SoundDemo.vehiclesAt(tMs),
                            source = DataSource.V2,
                            bikeSpeedMs = SoundDemo.bikeSpeedAt(tMs),
                        ),
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = if (captioned) stringResource(soundDemoCaption(lastCue)) else "",
            color = br.fg,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
