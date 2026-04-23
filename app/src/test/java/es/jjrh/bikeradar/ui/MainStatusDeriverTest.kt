// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainStatusDeriverTest {

    private fun baseInputs(
        firstRunComplete: Boolean = true,
        pausedUntilEpochMs: Long = 0L,
        hasBond: Boolean = true,
        radarFresh: Boolean = true,
        haErrorRecent: Boolean = false,
        dashcamOwned: Boolean = false,
        dashcamWarnWhenOff: Boolean = false,
        dashcamFresh: Boolean = false,
        dashcamDisplayName: String? = null,
    ) = MainStatusInputs(
        firstRunComplete, pausedUntilEpochMs, hasBond, radarFresh,
        haErrorRecent, dashcamOwned, dashcamWarnWhenOff, dashcamFresh, dashcamDisplayName,
    )

    private fun derive(inputs: MainStatusInputs, now: Long = 100L) =
        MainStatusDeriver.derive(inputs, now) { "HH:MM" }

    @Test fun firstRunTopsEverything() {
        val s = derive(baseInputs(firstRunComplete = false, hasBond = false))
        assertEquals(MainStatusIcon.PlayCircle, s.icon)
        assertEquals(MainStatusTone.Good, s.tone)
        assertEquals("Let's set up your radar", s.headline)
    }

    @Test fun pausedBeatsBondCheck() {
        val s = derive(baseInputs(hasBond = false, pausedUntilEpochMs = 200L), now = 100L)
        assertEquals(MainStatusIcon.PauseCircle, s.icon)
        assertEquals(MainStatusTone.Info, s.tone)
    }

    @Test fun notPairedFiresWhenBondMissing() {
        val s = derive(baseInputs(hasBond = false))
        assertEquals(MainStatusIcon.BluetoothDisabled, s.icon)
        assertEquals(MainStatusTone.Error, s.tone)
        assertEquals("Radar not paired", s.headline)
    }

    @Test fun dashcamOffBeatsHaDown() {
        // Both would fire — dashcam must win (rider safety).
        val s = derive(baseInputs(
            haErrorRecent = true,
            dashcamOwned = true,
            dashcamWarnWhenOff = true,
            dashcamFresh = false,
            dashcamDisplayName = "Vue-123",
        ))
        assertEquals(MainStatusIcon.Warning, s.icon)
        assertEquals(MainStatusTone.Warn, s.tone)
        assertEquals("Radar live, dashcam off", s.headline)
        assertEquals("Turn on your Vue-123", s.subtitle)
    }

    @Test fun dashcamOffFallsBackWhenNoNameStored() {
        val s = derive(baseInputs(
            dashcamOwned = true,
            dashcamWarnWhenOff = true,
            dashcamFresh = false,
        ))
        assertEquals("Turn on your dashcam", s.subtitle)
    }

    @Test fun haDownShowsWhenDashcamIsFine() {
        val s = derive(baseInputs(
            haErrorRecent = true,
            dashcamOwned = true,
            dashcamWarnWhenOff = true,
            dashcamFresh = true,
        ))
        assertEquals(MainStatusIcon.CheckCircle, s.icon)
        assertEquals("Radar live", s.headline)
        assertEquals("Home Assistant unreachable", s.subtitle)
    }

    @Test fun warnDisabledSkipsDashcamStateEvenWhenDashcamIsOff() {
        val s = derive(baseInputs(
            dashcamOwned = true,
            dashcamWarnWhenOff = false,
            dashcamFresh = false,
        ))
        assertEquals(MainStatusIcon.CheckCircle, s.icon)
        assertEquals("Radar live", s.headline)
    }

    @Test fun allGoodWithDashcamShowsDashcamSubtitle() {
        val s = derive(baseInputs(
            dashcamOwned = true,
            dashcamWarnWhenOff = true,
            dashcamFresh = true,
        ))
        assertEquals(MainStatusIcon.CheckCircle, s.icon)
        assertEquals("Radar live", s.headline)
        assertEquals("Dashcam on", s.subtitle)
    }

    @Test fun allGoodWithoutDashcamHasNoSubtitle() {
        val s = derive(baseInputs())
        assertEquals(MainStatusIcon.CheckCircle, s.icon)
        assertEquals("Radar live", s.headline)
        assertNull(s.subtitle)
    }

    @Test fun waitingWhenPairedButRadarStale() {
        val s = derive(baseInputs(radarFresh = false))
        assertEquals(MainStatusIcon.Sensors, s.icon)
        assertEquals(MainStatusTone.Neutral, s.tone)
        assertEquals("Waiting for radar", s.headline)
    }
}
