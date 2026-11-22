// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.RideHistoryRecord
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** TalkBack reads a ride row's stats as one line, not a node per segment. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideHistoryStatsRowA11yTest {

    @get:Rule val composeRule = createComposeRule()

    private val record = RideHistoryRecord(
        startedAtMs = 1_000L,
        endedAtMs = 2_000L,
        overtakes = 37,
        closePasses = 1,
        grazingPasses = 0,
        hgvClosePasses = 0,
        peakClosingKmh = null,
        closingSpeedP90Kmh = null,
        minLateralClearanceM = null,
        distanceKm = 5f,
        exposureSeconds = 0L,
        alertsPerKm = null,
        tightestPassClearanceM = 0.8f,
        tightestPassClosingKmh = null,
        closePassesCounted = true,
        vehiclesPassed = 8,
    )

    @Test
    fun theStatsRowReadsAsOneLineWithoutItsBullets() {
        composeRule.setContent { UiTheme { RideHistoryContent(records = listOf(record), onBack = {}) } }
        composeRule.onNodeWithContentDescription("37 vehicles detected behind, about 8 passed you (1 close), tightest 0.8 m")
            .assertExists()
        composeRule.onAllNodesWithText("detected behind", substring = true).assertCountEquals(0)
    }
}
