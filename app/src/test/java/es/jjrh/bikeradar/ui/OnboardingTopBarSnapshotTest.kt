// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Setup's top bar on a narrow phone, where "Skip setup" squeezes the
 *  progress segments most; Spanish is the longer label. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h120dp-xxhdpi")
class OnboardingTopBarSnapshotTest {

    @Test
    fun narrowPhone() {
        captureRoboImage { Bar() }
    }

    @Test
    @Config(qualifiers = "es-w360dp-h120dp-xxhdpi")
    fun narrowPhoneEs() {
        captureRoboImage { Bar() }
    }

    @Composable
    private fun Bar() {
        UiTheme {
            Box(modifier = Modifier.fillMaxSize().background(LocalBrColors.current.bg)) {
                TopProgress(currentPage = 2, onSkip = {})
            }
        }
    }
}
