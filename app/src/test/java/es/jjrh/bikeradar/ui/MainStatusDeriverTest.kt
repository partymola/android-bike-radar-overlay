// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.R
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

    // formatTime returns a fixed sentinel so tests can assert the clock
    // value is threaded into headlineArgs without depending on a real clock.
    private fun derive(inputs: MainStatusInputs, now: Long = 100L) = MainStatusDeriver.derive(inputs, now) { "HH:MM" }

    @Test fun firstRunTopsEverything() {
        val s = derive(baseInputs(firstRunComplete = false, hasBond = false))
        assertEquals(MainStatusIcon.PlayCircle, s.icon)
        assertEquals(MainStatusTone.Good, s.tone)
        assertEquals(R.string.main_status_setup_title, s.headlineRes)
    }

    @Test fun pausedBeatsBondCheck() {
        val s = derive(baseInputs(hasBond = false, pausedUntilEpochMs = 200L), now = 100L)
        assertEquals(MainStatusIcon.PauseCircle, s.icon)
        assertEquals(MainStatusTone.Info, s.tone)
        assertEquals(R.string.main_status_paused_title, s.headlineRes)
        // The injected formatTime result becomes the headline substitution.
        assertEquals(listOf("HH:MM"), s.headlineArgs)
    }

    @Test fun notPairedFiresWhenBondMissing() {
        val s = derive(baseInputs(hasBond = false))
        assertEquals(MainStatusIcon.BluetoothDisabled, s.icon)
        assertEquals(MainStatusTone.Error, s.tone)
        assertEquals(R.string.main_status_not_paired_title, s.headlineRes)
    }

    @Test fun dashcamOffBeatsHaDown() {
        // Both would fire — dashcam must win (rider safety).
        val s = derive(
            baseInputs(
                haErrorRecent = true,
                dashcamOwned = true,
                dashcamWarnWhenOff = true,
                dashcamFresh = false,
                dashcamDisplayName = "Vue-123",
            ),
        )
        assertEquals(MainStatusIcon.Warning, s.icon)
        assertEquals(MainStatusTone.Warn, s.tone)
        assertEquals(R.string.main_status_dashcam_off_title, s.headlineRes)
        assertEquals(R.string.main_status_dashcam_off_sub, s.subtitleRes)
        assertEquals(listOf("Vue-123"), s.subtitleArgs)
    }

    @Test fun dashcamOffFallsBackWhenNoNameStored() {
        val s = derive(
            baseInputs(
                dashcamOwned = true,
                dashcamWarnWhenOff = true,
                dashcamFresh = false,
            ),
        )
        assertEquals(R.string.main_status_dashcam_off_sub_generic, s.subtitleRes)
        assertEquals(emptyList<String>(), s.subtitleArgs)
    }

    @Test fun haDownShowsWhenDashcamIsFine() {
        val s = derive(
            baseInputs(
                haErrorRecent = true,
                dashcamOwned = true,
                dashcamWarnWhenOff = true,
                dashcamFresh = true,
            ),
        )
        assertEquals(MainStatusIcon.CheckCircle, s.icon)
        assertEquals(R.string.main_status_live_title, s.headlineRes)
        assertEquals(R.string.main_status_live_ha_down_sub, s.subtitleRes)
    }

    @Test fun warnDisabledSkipsDashcamStateEvenWhenDashcamIsOff() {
        val s = derive(
            baseInputs(
                dashcamOwned = true,
                dashcamWarnWhenOff = false,
                dashcamFresh = false,
            ),
        )
        assertEquals(MainStatusIcon.CheckCircle, s.icon)
        assertEquals(R.string.main_status_live_title, s.headlineRes)
    }

    @Test fun allGoodWithDashcamShowsDashcamSubtitle() {
        val s = derive(
            baseInputs(
                dashcamOwned = true,
                dashcamWarnWhenOff = true,
                dashcamFresh = true,
            ),
        )
        assertEquals(MainStatusIcon.CheckCircle, s.icon)
        assertEquals(R.string.main_status_live_title, s.headlineRes)
        assertEquals(R.string.main_status_live_dashcam_on_sub, s.subtitleRes)
    }

    @Test fun allGoodWithoutDashcamHasNoSubtitle() {
        val s = derive(baseInputs())
        assertEquals(MainStatusIcon.CheckCircle, s.icon)
        assertEquals(R.string.main_status_live_title, s.headlineRes)
        assertNull(s.subtitleRes)
    }

    @Test fun waitingWhenPairedButRadarStale() {
        val s = derive(baseInputs(radarFresh = false))
        assertEquals(MainStatusIcon.Sensors, s.icon)
        assertEquals(MainStatusTone.Neutral, s.tone)
        assertEquals(R.string.main_status_waiting_title, s.headlineRes)
    }

    @Test fun serviceStoppedFiresWhenServiceDisabled() {
        val s = derive(baseInputs(serviceEnabled = false))
        assertEquals(MainStatusIcon.PlayCircle, s.icon)
        assertEquals(MainStatusTone.Neutral, s.tone)
        assertEquals(R.string.main_status_service_off_title, s.headlineRes)
        assertEquals(R.string.main_status_service_off_sub, s.subtitleRes)
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
        assertEquals(R.string.main_status_service_off_title, s.headlineRes)
    }

    @Test fun firstRunStillBeatsServiceStopped() {
        // !firstRunComplete && !serviceEnabled is a corner case (a
        // first-run user with service-disabled prefs from a previous
        // install state) — first-run should still win so the user is
        // walked through onboarding rather than seeing a Start CTA.
        val s = derive(baseInputs(firstRunComplete = false, serviceEnabled = false))
        assertEquals(R.string.main_status_setup_title, s.headlineRes)
    }

    // ── bluetooth-off ────────────────────────────────────────────────────────

    @Test fun btOffFiresWhenAdapterDisabled() {
        val s = derive(baseInputs(bluetoothEnabled = false))
        assertEquals(MainStatusIcon.BluetoothDisabled, s.icon)
        assertEquals(MainStatusTone.Warn, s.tone)
        assertEquals(R.string.main_status_bt_off_title, s.headlineRes)
        assertEquals(R.string.main_status_bt_off_sub, s.subtitleRes)
    }

    @Test fun btOffBeatsNotPaired() {
        // Both would fire — BT-off wins because the bond persists
        // across BT toggles, so the right prompt is to turn BT back
        // on, not to go through the system pair flow.
        val s = derive(baseInputs(bluetoothEnabled = false, hasBond = false))
        assertEquals(R.string.main_status_bt_off_title, s.headlineRes)
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
        assertEquals(R.string.main_status_service_off_title, s.headlineRes)
    }

    @Test fun btOffWithRadarFreshStillFires() {
        // radarFresh can lag a BT toggle by up to one decoder timeout
        // window; the BT-off branch must still win so the rider isn't
        // told "Radar live" while the adapter is actually off.
        val s = derive(baseInputs(bluetoothEnabled = false, radarFresh = true))
        assertEquals(R.string.main_status_bt_off_title, s.headlineRes)
    }

    @Test fun firstRunBeatsBtOff() {
        // First-run is the top of the priority list; even with BT off
        // the user should see onboarding rather than the BT prompt.
        val s = derive(baseInputs(firstRunComplete = false, bluetoothEnabled = false))
        assertEquals(R.string.main_status_setup_title, s.headlineRes)
    }
}
