// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import es.jjrh.bikeradar.ClosePassStateBus
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** When the home close-pass card shows its figure, and what it says instead. */
@RunWith(RobolectricTestRunner::class)
class ClosePassCardFigureTest {

    @get:Rule val composeRule = createComposeRule()

    @Before fun zeroCount() = ClosePassStateBus.reset()

    @After fun leaveNoCount() = ClosePassStateBus.reset()

    private fun card(logging: Boolean, rangeOnly: Boolean = false) = composeRule.setContent {
        ClosePassStatsCard(loggingEnabled = logging, rideStored = true, rangeOnly = rangeOnly)
    }

    private fun containing(text: String) = composeRule.onAllNodesWithText(text, substring = true)

    @Test fun theHomeCardShowsNoZeroWithCountingOff() {
        card(logging = false)
        composeRule.onAllNodesWithText("0").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("0 close passes this ride").assertCountEquals(0)
        containing("Turn on close-pass counting").assertCountEquals(1)
    }

    @Test fun theHomeCardShowsItsZeroWithCountingOn() {
        card(logging = true)
        composeRule.onAllNodesWithText("0").assertCountEquals(1)
        containing("Turn on close-pass counting").assertCountEquals(0)
        containing("distance only").assertCountEquals(0)
    }

    @Test fun aPassCountedEarlierInTheSessionStillShowsWithTheHint() {
        ClosePassStateBus.increment(1)
        card(logging = false)
        composeRule.onAllNodesWithText("1").assertCountEquals(1)
        containing("Turn on close-pass counting").assertCountEquals(1)
    }

    @Test fun aDistanceOnlyRadarShowsNoZeroAndSaysWhy() {
        card(logging = true, rangeOnly = true)
        composeRule.onAllNodesWithText("0").assertCountEquals(0)
        containing("distance only").assertCountEquals(1)
    }

    @Test fun aDistanceOnlyRadarKeepsAnEarlierCountAndSaysWhyNoMoreAreCounted() {
        ClosePassStateBus.increment(1)
        card(logging = false, rangeOnly = true)
        composeRule.onAllNodesWithText("1").assertCountEquals(1)
        containing("distance only").assertCountEquals(1)
        containing("Turn on close-pass counting").assertCountEquals(0)
    }

    @Test fun aDistanceOnlyRadarIsNotToldToTurnCountingOn() {
        card(logging = false, rangeOnly = true)
        containing("distance only").assertCountEquals(1)
        containing("Turn on close-pass counting").assertCountEquals(0)
    }
}
