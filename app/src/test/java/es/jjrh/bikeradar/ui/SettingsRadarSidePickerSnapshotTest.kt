// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden of the screen-side picker open on the stored Left. It pins the drawn
 * selection dot, which the radio semantics in [SettingsRadarOverlaySideTest]
 * cannot see. A dialog is its own window, so the whole screen is captured.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsRadarSidePickerSnapshotTest {

    @get:Rule val composeRule = createComposeRule()

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun leftChosen() {
        composeRule.setContent {
            UiTheme { OverlaySidePicker(onLeft = true, onSelect = {}, onDismiss = {}) }
        }
        composeRule.waitForIdle()
        captureScreenRoboImage()
    }
}
