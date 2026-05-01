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
        serviceEnabled: Boolean = true,
        bluetoothEnabled: Boolean = true,
    ) = MainStatusInputs(
        firstRunComplete = firstRunComplete,
        pausedUntilEpochMs = pausedUntilEpochMs,
        hasBond = hasBond,
        radarFresh = radarFresh,
        haErrorRecent = haErrorRecent,
        dashcamOwned = dashcamOwned,
        dashcamWarnWhenOff = dashcamWarnWhenOff,
        dashcamFresh = dashcamFresh,
        dashcamDisplayName = dashcamDisplayName,
        serviceEnabled = serviceEnabled,
        bluetoothEnabled = bluetoothEnabled,
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

    @Test fun serviceStoppedFiresWhenServiceDisabled() {
        val s = derive(baseInputs(serviceEnabled = false))
        assertEquals(MainStatusIcon.PlayCircle, s.icon)
        assertEquals(MainStatusTone.Neutral, s.tone)
        assertEquals("Service stopped", s.headline)
        assertEquals("Tap Start to begin", s.subtitle)
    }

    @Test fun serviceStoppedBeatsPausedAndNotPaired() {
        // All three would fire — service-stopped must win because a
        // stopped service means nothing is scanning, so saying "not
        // paired" or "paused" would be misdirection.
        val s = derive(
            baseInputs(
                serviceEnabled = false,
                pausedUntilEpochMs = 200L,
                hasBond = false,
            ),
            now = 100L,
        )
        assertEquals("Service stopped", s.headline)
    }

    @Test fun firstRunStillBeatsServiceStopped() {
        // !firstRunComplete && !serviceEnabled is a corner case (a
        // first-run user with service-disabled prefs from a previous
        // install state) — first-run should still win so the user is
        // walked through onboarding rather than seeing a Start CTA.
        val s = derive(baseInputs(firstRunComplete = false, serviceEnabled = false))
        assertEquals("Let's set up your radar", s.headline)
    }

    // ── bluetooth-off ────────────────────────────────────────────────────────

    @Test fun btOffFiresWhenAdapterDisabled() {
        val s = derive(baseInputs(bluetoothEnabled = false))
        assertEquals(MainStatusIcon.BluetoothDisabled, s.icon)
        assertEquals(MainStatusTone.Warn, s.tone)
        assertEquals("Bluetooth is off", s.headline)
        assertEquals("Radar is offline", s.subtitle)
    }

    @Test fun btOffBeatsNotPaired() {
        // Both would fire — BT-off wins because the bond persists
        // across BT toggles, so the right prompt is to turn BT back
        // on, not to go through the system pair flow.
        val s = derive(baseInputs(bluetoothEnabled = false, hasBond = false))
        assertEquals("Bluetooth is off", s.headline)
    }

    @Test fun pausedBeatsBtOff() {
        // Pause is the user's explicit "alerts silenced for X minutes"
        // intent. Showing BT-off would deflect from that.
        val s = derive(
            baseInputs(bluetoothEnabled = false, pausedUntilEpochMs = 200L),
            now = 100L,
        )
        assertEquals(MainStatusIcon.PauseCircle, s.icon)
    }

    @Test fun serviceStoppedBeatsBtOff() {
        // Service-stopped wins because nothing is scanning regardless
        // of the adapter state.
        val s = derive(baseInputs(serviceEnabled = false, bluetoothEnabled = false))
        assertEquals("Service stopped", s.headline)
    }

    @Test fun btOffWithRadarFreshStillFires() {
        // radarFresh can lag a BT toggle by up to one decoder timeout
        // window; the BT-off branch must still win so the rider isn't
        // told "Radar live" while the adapter is actually off.
        val s = derive(baseInputs(bluetoothEnabled = false, radarFresh = true))
        assertEquals("Bluetooth is off", s.headline)
    }

    @Test fun firstRunBeatsBtOff() {
        // First-run is the top of the priority list; even with BT off
        // the user should see onboarding rather than the BT prompt.
        val s = derive(baseInputs(firstRunComplete = false, bluetoothEnabled = false))
        assertEquals("Let's set up your radar", s.headline)
    }
}
