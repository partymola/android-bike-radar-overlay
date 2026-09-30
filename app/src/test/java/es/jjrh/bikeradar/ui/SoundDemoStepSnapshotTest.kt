// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.AlertCue
import es.jjrh.bikeradar.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the sound demo leaf at three moments of the scene:
 * before Play, mid-scene as the first car reaches three beeps, and mid-scene
 * at the urgent warning, plus the three-beep moment in Spanish, where the sub
 * runs longest, and the Settings replay locked by a streaming radar, in Spanish.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SoundDemoStepSnapshotTest {

    private fun capture(
        tMs: Long,
        lastCue: AlertCue?,
        played: Boolean,
        playing: Boolean = false,
        canPlay: Boolean = true,
        mark: Int = R.string.sound_demo_mark,
        doneLabel: Int = R.string.common_continue,
    ) {
        captureRoboImage {
            UiTheme {
                Box(modifier = Modifier.fillMaxSize().background(LocalBrColors.current.bg)) {
                    SoundDemoStepContent(
                        tMs = tMs,
                        lastCue = lastCue,
                        playing = playing,
                        played = played,
                        canPlay = canPlay,
                        onPlay = {},
                        onContinue = {},
                        mark = mark,
                        doneLabel = doneLabel,
                    )
                }
            }
        }
    }

    @Test
    fun beforePlay() = capture(tMs = 0L, lastCue = null, played = false)

    @Test
    fun threeBeeps() = capture(tMs = 7_000L, lastCue = AlertCue.Beep(3), played = true, playing = true)

    @Test
    fun urgent() = capture(tMs = 16_000L, lastCue = AlertCue.Urgent, played = true, playing = true)

    @Test
    @Config(qualifiers = "+es")
    fun threeBeepsEs() = capture(tMs = 7_000L, lastCue = AlertCue.Beep(3), played = true, playing = true)

    @Test
    @Config(qualifiers = "+es")
    fun lockedReplayEs() = capture(
        tMs = 0L,
        lastCue = null,
        played = true,
        canPlay = false,
        mark = R.string.alert_sounds_title,
        doneLabel = R.string.common_done,
    )
}
