// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the notice says, and that the button is the only way past it.
 *
 * The three limits are asserted individually rather than through a golden:
 * a golden goes green on a screen that renders two of the three and pads
 * the gap, because nobody re-reads a picture.
 */
@RunWith(AndroidJUnit4::class)
class SafetyNoticeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun itSaysWhatTheRadarCannotDo() {
        compose.setContent { SafetyNoticeScreen(onAcknowledge = {}) }

        compose.onNodeWithText("Before you ride").assertIsDisplayed()
        compose.onNodeWithText("It cannot look behind for you.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("It can miss a vehicle, or warn late.", substring = true).assertIsDisplayed()
        // The SUBJECT, not just "without notice". Trimmed to the radar link
        // alone the shorter fragment still passes, and the sound is the half
        // that matters most: audio is the channel a rider actually rides on.
        compose.onNodeWithText("or the sound can drop without notice", substring = true).assertIsDisplayed()
        compose.onNodeWithText("only means the radar sees nothing", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Always look behind before you move out.").assertIsDisplayed()
    }

    @Test
    fun theButtonIsWhatAcknowledgesIt() {
        var acknowledged = 0
        compose.setContent { SafetyNoticeScreen(onAcknowledge = { acknowledged++ }) }

        assertEquals("nothing should be acknowledged before the tap", 0, acknowledged)
        compose.onNodeWithText("I understand").performClick()
        assertEquals(1, acknowledged)
    }

    /**
     * The Spanish half, asserted as text rather than left to the golden.
     *
     * Lint catches a DELETED key, not a narrowed one: a bullet edited to drop
     * "o avisar tarde" keeps its key, passes `MissingTranslation`, and shows up
     * only as a pixel diff somebody has to read and interpret. That is the
     * failure the repo already documents for the privacy strings, and this is a
     * safety notice.
     */
    @Test
    @Config(qualifiers = "es")
    fun itSaysTheSameInSpanish() {
        compose.setContent { SafetyNoticeScreen(onAcknowledge = {}) }

        compose.onNodeWithText("Antes de montar en bici").assertIsDisplayed()
        compose.onNodeWithText("No mira atrás por ti", substring = true).assertIsDisplayed()
        // The whole sentence: the tail alone drops "no detectar un vehículo",
        // which is the half that says the radar can miss a car outright.
        compose.onNodeWithText("Puede no detectar un vehículo o avisar tarde.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("o el sonido pueden fallar sin aviso", substring = true).assertIsDisplayed()
        compose.onNodeWithText("solo significa que el radar no ve nada", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Mira siempre atrás antes de comenzar la marcha.").assertIsDisplayed()
        compose.onNodeWithText("Entendido").assertIsDisplayed()
    }

    /**
     * The heading appears once, not twice.
     *
     * A screen header stacked above a body that renders its own H1 puts the
     * title on screen twice, which is invisible to every text assertion above:
     * each of them passes on either of the two nodes.
     *
     * This says nothing about the About route. That route is tapped through in
     * `SafetyNoticeAboutRouteTest`, which is what would fail if it stopped
     * leading here.
     */
    @Test
    fun theTitleRendersExactlyOnce() {
        compose.setContent { SafetyNoticeScreen(onAcknowledge = {}) }

        assertEquals(
            "the notice renders its title twice",
            1,
            compose.onAllNodesWithText("Before you ride").fetchSemanticsNodes().size,
        )
    }
}
