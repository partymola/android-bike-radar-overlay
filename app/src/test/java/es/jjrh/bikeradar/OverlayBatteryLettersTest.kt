// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The two letter resources resolve to the expected letter in each locale, and
 * the show-labels subtitle takes the radar letter in its first slot and the
 * dashcam letter in its second.
 *
 * The slot check formats the subtitle with sentinel arguments that appear in no
 * string, so a subtitle with a letter written into its text instead of a
 * placeholder fails it. That the overlay draws these resources is pinned by the
 * `batteryLow*` and `dashcamMissingWithRadarLow*` goldens in
 * [RadarOverlayViewTest], and that Settings passes them to the subtitle is
 * what the Settings goldens show; neither is checked here.
 *
 * Literals throughout: reading the expected value back from the resource under
 * test would stay green whatever the resource became.
 */
@RunWith(AndroidJUnit4::class)
class OverlayBatteryLettersTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun letters(): Pair<String, String> = context.getString(R.string.overlay_battery_letter_radar) to
        context.getString(R.string.overlay_battery_letter_dashcam)

    private fun subtitle(radar: String, dashcam: String): String = context.getString(R.string.settings_radar_show_labels_subtitle, radar, dashcam)

    private fun subtitleWithLetters(): String {
        val (radar, dashcam) = letters()
        return subtitle(radar, dashcam)
    }

    @Test
    fun englishLettersAreRAndD() {
        assertEquals("R" to "D", letters())
        assertTrue(subtitleWithLetters(), subtitleWithLetters().contains("shows R for the radar and D for the dashcam"))
    }

    @Test
    fun englishSubtitleTakesRadarThenDashcam() {
        assertTrue(subtitle("Ⓧ", "Ⓨ"), subtitle("Ⓧ", "Ⓨ").contains("shows Ⓧ for the radar and Ⓨ for the dashcam"))
    }

    @Test
    @Config(qualifiers = "es")
    fun spanishLettersAreRAndC() {
        assertEquals("R" to "C", letters())
        assertTrue(subtitleWithLetters(), subtitleWithLetters().contains("una R para el radar y una C para la cámara"))
    }

    @Test
    @Config(qualifiers = "es")
    fun spanishSubtitleTakesRadarThenDashcam() {
        assertTrue(subtitle("Ⓧ", "Ⓨ"), subtitle("Ⓧ", "Ⓨ").contains("una Ⓧ para el radar y una Ⓨ para la cámara"))
    }

    @Test
    @Config(qualifiers = "pl")
    fun polishLettersAreRAndK() {
        assertEquals("R" to "K", letters())
        assertTrue(subtitleWithLetters(), subtitleWithLetters().contains("literę R dla radaru i K dla kamery"))
    }

    @Test
    @Config(qualifiers = "pl")
    fun polishSubtitleTakesRadarThenDashcam() {
        assertTrue(subtitle("Ⓧ", "Ⓨ"), subtitle("Ⓧ", "Ⓨ").contains("literę Ⓧ dla radaru i Ⓨ dla kamery"))
    }
}
