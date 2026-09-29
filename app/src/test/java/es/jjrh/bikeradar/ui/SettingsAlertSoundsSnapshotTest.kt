// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Roborazzi goldens for the alert-sounds glossary, playable and while the radar streams, plus Spanish. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsAlertSoundsSnapshotTest {

    private fun capture(canPlay: Boolean) {
        captureRoboImage {
            UiTheme {
                SettingsAlertSoundsContent(canPlay = canPlay, onBack = {}, onPlay = {}, onWatchDemo = {})
            }
        }
    }

    @Test
    fun playable() = capture(canPlay = true)

    @Test
    fun whileRiding() = capture(canPlay = false)

    @Test
    @Config(qualifiers = "+es")
    fun playableEs() = capture(canPlay = true)
}
