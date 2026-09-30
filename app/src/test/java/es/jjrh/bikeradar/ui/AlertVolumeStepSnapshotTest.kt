// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Roborazzi goldens for the alert volume step in onboarding, in Spanish, and locked in Settings. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class AlertVolumeStepSnapshotTest {

    private fun capture(
        canPlay: Boolean = true,
        mark: Int = R.string.sound_demo_mark,
        doneLabel: Int = R.string.common_continue,
    ) {
        captureRoboImage {
            UiTheme {
                Box(modifier = Modifier.fillMaxSize().background(LocalBrColors.current.bg)) {
                    AlertVolumeStepContent(
                        volume = 70,
                        canPlay = canPlay,
                        mark = mark,
                        onVolumeChange = {},
                        onPlay = {},
                        onContinue = {},
                        doneLabel = doneLabel,
                    )
                }
            }
        }
    }

    @Test
    fun onboarding() = capture()

    @Test
    @Config(qualifiers = "+es")
    fun onboardingEs() = capture()

    @Test
    fun lockedInSettings() = capture(canPlay = false, mark = R.string.alert_sounds_title, doneLabel = R.string.common_done)
}
