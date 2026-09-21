// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens for the riding-aid notice in both languages. Spanish runs longer
 * than English, and this screen is the one a rider reads once and never
 * again, so clipping here would not be reported.
 *
 * Two, not three: Settings, About re-opens this same composable rather
 * than a variant of it, so a third golden would record the same pixels.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SafetyNoticeSnapshotTest {

    @Test
    @Config(qualifiers = "w448dp-h997dp-xxhdpi")
    fun gate() {
        captureRoboImage {
            SafetyNoticeScreen(onAcknowledge = {})
        }
    }

    @Test
    @Config(qualifiers = "es-w448dp-h997dp-xxhdpi")
    fun gateEs() {
        captureRoboImage {
            SafetyNoticeScreen(onAcknowledge = {})
        }
    }
}
