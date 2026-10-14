// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import es.jjrh.bikeradar.ui.RideHistoryContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A ride with close-pass counting off shows no close-pass figure. */
@RunWith(RobolectricTestRunner::class)
class ClosePassCountingRecordTest {

    @get:Rule val composeRule = createComposeRule()

    private fun record(counted: Boolean?) = RideHistoryRecord(
        startedAtMs = 1_780_733_520_000L,
        endedAtMs = 1_780_733_520_000L + 30 * 60_000L,
        overtakes = 12,
        closePasses = 0,
        grazingPasses = 0,
        hgvClosePasses = 0,
        peakClosingKmh = 40,
        closingSpeedP90Kmh = null,
        minLateralClearanceM = null,
        distanceKm = 7.4f,
        exposureSeconds = 300L,
        alertsPerKm = 1.0f,
        tightestPassClearanceM = null,
        tightestPassClosingKmh = null,
        closePassesCounted = counted,
    )

    @Test fun aRideStartsUncountedUntilCountingRuns() {
        val stats = RideStatsAccumulator()
        assertFalse(stats.snapshot().closePassesCounted)
        stats.markClosePassesCounted()
        assertTrue(stats.snapshot().closePassesCounted)
    }

    @Test fun aCountedPassMarksTheRideCounted() {
        val stats = RideStatsAccumulator()
        stats.observeClosePass(
            ClosePassDetector.Event(
                timestampMs = 1L,
                clearanceM = 0.8f,
                side = ClosePassDetector.Side.RIGHT,
                rangeYM = 1f,
                closingSpeedKmh = 30,
                riderSpeedKmh = 20,
                vehicleSize = VehicleSize.CAR,
                emitThresholdM = 1.0f,
                severity = ClosePassDetector.Severity.VERY_CLOSE,
            ),
        )
        assertTrue(stats.snapshot().closePassesCounted)
    }

    @Test fun aReconnectKeepsTheRideCounted() {
        val stats = RideStatsAccumulator().also { it.markClosePassesCounted() }
        stats.dropOpenTracks()
        assertTrue(stats.snapshot().closePassesCounted)
    }

    @Test fun onlyTheFirstMarkAsksForAPublish() {
        val stats = RideStatsAccumulator()
        stats.markPublished()
        stats.markClosePassesCounted()
        assertTrue(stats.changedSinceLast())
        stats.markPublished()
        stats.markClosePassesCounted()
        assertFalse(stats.changedSinceLast())
    }

    @Test fun theStoredRideCarriesWhetherItWasCounted() {
        val counted = RideStatsAccumulator().also { it.markClosePassesCounted() }
        assertEquals(true, RideHistoryRecord.fromSnapshot(counted.snapshot(), endedAtMs = 1L).closePassesCounted)
        assertEquals(false, RideHistoryRecord.fromSnapshot(RideStatsAccumulator().snapshot(), endedAtMs = 1L).closePassesCounted)
        for (c in listOf(true, false)) {
            val line = record(c).toJsonLine()
            assertTrue(line, line.contains("\"close_passes_counted\":$c"))
            assertEquals(c, RideHistoryRecord.fromJsonLine(line)!!.closePassesCounted)
        }
    }

    @Test fun aRideStoredBeforeTheFieldExistedIsUnknown() {
        val base = """{"v":2,"start_ms":1,"end_ms":2,"overtakes":3,"close_passes":0,"distance_km":1.0,"exposure_s":0"""
        assertNull(RideHistoryRecord.fromJsonLine("$base}")!!.closePassesCounted)
        assertNull(RideHistoryRecord.fromJsonLine("""$base,"close_passes_counted":null}""")!!.closePassesCounted)
    }

    @Test fun anUncountedRideShowsNoClosePassFigure() {
        composeRule.setContent { RideHistoryContent(records = listOf(record(counted = false)), onBack = {}) }
        composeRule.onAllNodesWithText("close pass", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("12 overtakes", substring = true).assertCountEquals(1)
    }

    @Test fun aCountedRideWithNoneSaysSo() {
        composeRule.setContent { RideHistoryContent(records = listOf(record(counted = true)), onBack = {}) }
        composeRule.onAllNodesWithText("0 close passes", substring = true).assertCountEquals(1)
    }

    @Test fun aRideOfUnknownCountingKeepsItsFigure() {
        composeRule.setContent { RideHistoryContent(records = listOf(record(counted = null)), onBack = {}) }
        composeRule.onAllNodesWithText("0 close passes", substring = true).assertCountEquals(1)
    }
}
