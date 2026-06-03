// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the slider value -> ladder label mapping for the SettingsRadar
 * "Overlay dimmer" slider. Slider is stepped at 4 stops between 0.5
 * and 1.0; each stop must land on a distinct label so the user sees
 * the value change as they drag.
 *
 * Runs under Robolectric so the externalised labels resolve from the real
 * string resources.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayDimLabelTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun fullOpacityShowsOff() = assertEquals("Off", overlayDimLabel(ctx, 1.0f))

    @Test fun lightStop() = assertEquals("Light", overlayDimLabel(ctx, 0.833f))

    @Test fun mediumStop() = assertEquals("Medium", overlayDimLabel(ctx, 0.667f))

    @Test fun strongFloor() = assertEquals("Strong", overlayDimLabel(ctx, 0.5f))

    /**
     * Boundary near the Off threshold (0.95). A drag that lands at 0.94
     * must read "Light" (one click below Off), not "Off" — otherwise the
     * 4-stop ladder collapses to 3 visible labels.
     */
    @Test fun justBelowOffStaysLight() = assertEquals("Light", overlayDimLabel(ctx, 0.94f))

    /**
     * If the persistent pref ever stores a value below the 0.5 floor
     * (legacy install before the floor was raised), the helper must
     * still produce a label rather than crash. Strong is the right
     * answer because anything below 0.5 is "max dim".
     */
    @Test fun belowFloorReturnsStrong() = assertEquals("Strong", overlayDimLabel(ctx, 0.4f))
}
