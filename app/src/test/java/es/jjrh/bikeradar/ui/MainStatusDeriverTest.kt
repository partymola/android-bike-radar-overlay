// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.MissingPermission
import es.jjrh.bikeradar.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainStatusDeriverTest {

    private fun baseInputs(
        pausedUntilEpochMs: Long = 0L,
        hasBond: Boolean = true,
        radarFresh: Boolean = true,
        radarLimited: Boolean = false,
        haErrorRecent: Boolean = false,
        dashcamOwned: Boolean = false,
        dashcamWarnWhenOff: Boolean = false,
        dashcamFresh: Boolean = false,
        dashcamDisplayName: String? = null,
        serviceEnabled: Boolean = true,
        bluetoothEnabled: Boolean = true,
        rideEndOfferable: Boolean = false,
        missingPermission: MissingPermission? = null,
        radarConnecting: Boolean = false,
    ) = MainStatusInputs(
        radarConnecting = radarConnecting,
        missingPermission = missingPermission,
        rideEndOfferable = rideEndOfferable,
        pausedUntilEpochMs = pausedUntilEpochMs,
        hasBond = hasBond,
        radarFresh = radarFresh,
        radarLimited = radarLimited,
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

    @Test fun theNotPairedCardSaysWherePairLeads() {
        val android = derive(baseInputs(hasBond = false))
        assertEquals(R.string.main_status_not_paired_sub, android.subtitleRes)
        val pick = derive(baseInputs(hasBond = false).copy(radarPickOffered = true))
        assertEquals(R.string.main_status_not_paired_title, pick.headlineRes)
        assertEquals(R.string.main_status_not_paired_pick_sub, pick.subtitleRes)
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

    // ── permissions missing ──────────────────────────────────────────────────

    @Test fun aMissingPermissionSaysWhichOne() {
        val nearby = derive(baseInputs(missingPermission = MissingPermission.NEARBY_DEVICES))
        assertEquals(MainStatusIcon.Warning, nearby.icon)
        assertEquals(MainStatusTone.Error, nearby.tone)
        assertEquals(R.string.main_status_permissions_title, nearby.headlineRes)
        assertEquals(R.string.main_status_permissions_nearby_sub, nearby.subtitleRes)
        val notifications = derive(baseInputs(missingPermission = MissingPermission.NOTIFICATIONS))
        assertEquals(MainStatusIcon.Warning, notifications.icon)
        // Amber: the radar runs and beeps without it.
        assertEquals(MainStatusTone.Warn, notifications.tone)
        assertEquals(R.string.main_status_permissions_title, notifications.headlineRes)
        assertEquals(R.string.main_status_permissions_notifications_sub, notifications.subtitleRes)
    }

    @Test fun theNotificationsCardNamesTheAlarmOnlyWhenOneCanSound() {
        // Every rider loses Pause; only one whose walk-away alarm can sound
        // loses the way to silence it.
        val n = MissingPermission.NOTIFICATIONS
        val plain = R.string.main_status_permissions_notifications_sub
        val alarm = R.string.main_status_permissions_notifications_alarm_sub
        fun sub(owned: Boolean, warn: Boolean, walkAway: Boolean) = derive(
            baseInputs(missingPermission = n, dashcamOwned = owned, dashcamWarnWhenOff = warn, dashcamFresh = true)
                .copy(walkAwayAlarmOn = walkAway),
        ).subtitleRes
        assertEquals(alarm, sub(owned = true, warn = true, walkAway = true))
        assertEquals(plain, sub(owned = false, warn = true, walkAway = true))
        // The overlay's camera-off warning does not gate the alarm.
        assertEquals(alarm, sub(owned = true, warn = false, walkAway = true))
        assertEquals(plain, sub(owned = true, warn = true, walkAway = false))
        // The radar-left-on alarm needs no camera.
        fun radarSub(on: Boolean) = derive(
            baseInputs(missingPermission = n).copy(radarLeftOnAlarmOn = on),
        ).subtitleRes
        assertEquals(alarm, radarSub(on = true))
        assertEquals(plain, radarSub(on = false))
    }

    @Test fun missingNotificationsTakesOnlyTheQuietCards() {
        // It replaces "Radar live" and "Waiting for radar", which carry no
        // warning or button of their own, and nothing else.
        val n = MissingPermission.NOTIFICATIONS
        val title = R.string.main_status_permissions_title
        assertEquals(title, derive(baseInputs(missingPermission = n)).headlineRes)
        assertEquals(title, derive(baseInputs(missingPermission = n, haErrorRecent = true)).headlineRes)
        assertEquals(title, derive(baseInputs(missingPermission = n, radarFresh = false)).headlineRes)
        // Above all the parked question: its button is the only way to stop
        // the drop cue repeating for a rider with no eBike.
        assertEquals(
            R.string.main_status_ride_over_title,
            derive(baseInputs(missingPermission = n, radarFresh = false, rideEndOfferable = true)).headlineRes,
        )
        assertEquals(
            R.string.main_status_connecting_title,
            derive(baseInputs(missingPermission = n, radarFresh = false, radarConnecting = true)).headlineRes,
        )
        assertEquals(
            R.string.main_status_limited_title,
            derive(baseInputs(missingPermission = n, radarLimited = true)).headlineRes,
        )
        assertEquals(
            R.string.main_status_dashcam_off_title,
            derive(baseInputs(missingPermission = n, dashcamOwned = true, dashcamWarnWhenOff = true)).headlineRes,
        )
        assertEquals(
            R.string.main_status_bt_off_title,
            derive(baseInputs(missingPermission = n, bluetoothEnabled = false)).headlineRes,
        )
        assertEquals(R.string.main_status_not_paired_title, derive(baseInputs(missingPermission = n, hasBond = false)).headlineRes)
        assertEquals(
            R.string.main_status_paused_title,
            derive(baseInputs(missingPermission = n, pausedUntilEpochMs = 200L), now = 100L).headlineRes,
        )
    }

    @Test fun aMissingPermissionBeatsPausedBluetoothAndPairing() {
        // Without Nearby devices the app does not start the service, and the
        // bonded list reads empty, so "not paired" would be the wrong fix.
        val s = derive(
            baseInputs(
                missingPermission = MissingPermission.NEARBY_DEVICES,
                pausedUntilEpochMs = 200L,
                bluetoothEnabled = false,
                hasBond = false,
            ),
            now = 100L,
        )
        assertEquals(R.string.main_status_permissions_title, s.headlineRes)
    }

    @Test fun serviceStoppedStillBeatsAMissingPermission() {
        val s = derive(baseInputs(serviceEnabled = false, missingPermission = MissingPermission.NEARBY_DEVICES))
        assertEquals(R.string.main_status_service_off_title, s.headlineRes)
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

    @Test fun aLimitedRadarIsNotReportedAsAllGood() {
        // The hero is the app's largest "you are covered" signal. A range-only
        // radar delivers frames, so the old radarFresh branch painted it green
        // and identical to a healthy one, while the urgent cue was held shut.
        val model = derive(baseInputs(radarFresh = true, radarLimited = true))
        assertEquals(MainStatusTone.Warn, model.tone)
        assertEquals(MainStatusIcon.Warning, model.icon)
        assertEquals(R.string.main_status_limited_title, model.headlineRes)
    }

    @Test fun theLimitedWarningOutranksTheDashcamWarning() {
        // Both are Warn, so only precedence decides which the rider reads.
        // The radar is the safety-critical device and the reason a cue they
        // expect will not sound; a dashcam left off is neither.
        val model = derive(
            baseInputs(
                radarFresh = true,
                radarLimited = true,
                dashcamOwned = true,
                dashcamWarnWhenOff = true,
                dashcamFresh = false,
            ),
        )
        assertEquals(R.string.main_status_limited_title, model.headlineRes)
    }

    @Test fun aFullRadarIsStillReportedAsLive() {
        // The other direction, so the branch cannot widen into always-warning.
        val model = derive(baseInputs(radarFresh = true, radarLimited = false))
        assertEquals(MainStatusTone.Good, model.tone)
        assertEquals(R.string.main_status_live_title, model.headlineRes)
    }

    @Test fun limitedOnlyMattersWhileFramesArrive() {
        // With nothing arriving there is no source to qualify; the honest
        // answer is the waiting state, not a limitation claim.
        val model = derive(baseInputs(radarFresh = false, radarLimited = true))
        assertEquals(R.string.main_status_waiting_title, model.headlineRes)
    }

    @Test fun theWaitingCardAsksInsteadOfInstructingOnceParkedIsOffered() {
        // The card would otherwise carry two opposite instructions: "Turn on
        // your radar" above a button saying the ride is over. Asking makes the
        // button the answer.
        val model = derive(baseInputs(radarFresh = false, rideEndOfferable = true))
        assertEquals(R.string.main_status_ride_over_title, model.headlineRes)
        assertEquals(R.string.main_status_ride_over_sub, model.subtitleRes)
    }

    @Test fun theWaitingCardStillInstructsWhenParkedIsNotOffered() {
        // Before the threshold, and for a session that never had a radar, the
        // instruction is still the right thing to show.
        val model = derive(baseInputs(radarFresh = false, rideEndOfferable = false))
        assertEquals(R.string.main_status_waiting_title, model.headlineRes)
        assertEquals(R.string.main_status_waiting_sub, model.subtitleRes)
    }

    @Test fun offeringParkedDoesNotRepaintTheCard() {
        // Icon and tone must not move: nothing about the radar changed, only
        // what the app can offer. Amber here would put the loudest colour on
        // the card at the moment the app is least sure anything is wrong.
        val asking = derive(baseInputs(radarFresh = false, rideEndOfferable = true))
        val waiting = derive(baseInputs(radarFresh = false, rideEndOfferable = false))
        assertEquals(MainStatusIcon.Sensors, asking.icon)
        assertEquals(MainStatusTone.Neutral, asking.tone)
        assertEquals(waiting.icon, asking.icon)
        assertEquals(waiting.tone, asking.tone)
    }

    // ── connecting ───────────────────────────────────────────────────────────

    @Test fun aLinkWithNoDataYetReadsConnecting() {
        // The System row already says "Connecting…" here; the card must not
        // tell the rider to turn on a radar the app is talking to.
        val s = derive(baseInputs(radarFresh = false, radarConnecting = true))
        assertEquals(MainStatusIcon.Sensors, s.icon)
        assertEquals(MainStatusTone.Warn, s.tone)
        assertEquals(R.string.main_status_connecting_title, s.headlineRes)
        // No advice yet: an ordinary connect is over in seconds.
        assertNull(s.subtitleRes)
        val long = derive(baseInputs(radarFresh = false, radarConnecting = true).copy(connectingAdviceDue = true))
        assertEquals(R.string.main_status_connecting_sub, long.subtitleRes)
    }

    @Test fun theParkedQuestionBeatsConnecting() {
        // A radar failing its setup after a ride is both. The question carries
        // "I've parked", the only control that stops the drop cue repeating
        // for a rider with no eBike.
        val s = derive(baseInputs(radarFresh = false, radarConnecting = true, rideEndOfferable = true))
        assertEquals(R.string.main_status_ride_over_title, s.headlineRes)
    }

    @Test fun aLiveRadarIsNotReportedAsConnecting() {
        // The link stays up while frames flow, so connecting is true then too.
        val s = derive(baseInputs(radarFresh = true, radarConnecting = true))
        assertEquals(R.string.main_status_live_title, s.headlineRes)
    }

    // ── pin off the named radars ─────────────────────────────────────────────

    @Test fun aPinOffTheNamedRadarsAsksWhichRadar() {
        val s = derive(baseInputs(radarFresh = false).copy(pinnedOffRadarName = "Pixel Buds"))
        assertEquals(MainStatusIcon.Sensors, s.icon)
        assertEquals(MainStatusTone.Neutral, s.tone)
        assertEquals(R.string.main_status_wrong_pin_title, s.headlineRes)
        assertEquals(R.string.main_status_wrong_pin_sub, s.subtitleRes)
        assertEquals(listOf("Pixel Buds"), s.subtitleArgs)
    }

    @Test fun aPinOffTheNamedRadarsBeatsConnectingAndItsAdvice() {
        // A pin the app does connect to, such as a stale front-camera pin
        // linked from the known-device cache, reads Connecting; the power-cycle
        // advice alone would send the rider to the wrong device.
        val s = derive(
            baseInputs(radarFresh = false, radarConnecting = true)
                .copy(pinnedOffRadarName = "Pixel Buds", connectingAdviceDue = true),
        )
        assertEquals(R.string.main_status_wrong_pin_title, s.headlineRes)
        assertEquals(R.string.main_status_wrong_pin_advice_sub, s.subtitleRes)
        assertEquals(listOf("Pixel Buds"), s.subtitleArgs)
        // Before the advice is due the device is on, so no "turn it on".
        val early = derive(
            baseInputs(radarFresh = false, radarConnecting = true).copy(pinnedOffRadarName = "Pixel Buds"),
        )
        assertEquals(R.string.main_status_wrong_pin_connecting_sub, early.subtitleRes)
        assertEquals(listOf("Pixel Buds"), early.subtitleArgs)
        // A stretch past the threshold in a pause between attempts is not
        // connecting, so the device may be off: no advice to power-cycle it.
        val pause = derive(
            baseInputs(radarFresh = false).copy(pinnedOffRadarName = "Pixel Buds", connectingAdviceDue = true),
        )
        assertEquals(R.string.main_status_wrong_pin_sub, pause.subtitleRes)
    }

    @Test fun aPinOffTheNamedRadarsBeatsTheNotificationsCard() {
        val s = derive(
            baseInputs(radarFresh = false, missingPermission = MissingPermission.NOTIFICATIONS)
                .copy(pinnedOffRadarName = "Pixel Buds"),
        )
        assertEquals(R.string.main_status_wrong_pin_title, s.headlineRes)
    }

    @Test fun theParkedQuestionAndALiveRadarBeatAPinOffTheNamedRadars() {
        // The parked question carries the only control that stops the drop cue
        // repeating; a pinned device that streams is a radar.
        val parked = derive(baseInputs(radarFresh = false, rideEndOfferable = true).copy(pinnedOffRadarName = "Pixel Buds"))
        assertEquals(R.string.main_status_ride_over_title, parked.headlineRes)
        val live = derive(baseInputs(radarFresh = true).copy(pinnedOffRadarName = "Unlisted radar"))
        assertEquals(R.string.main_status_live_title, live.headlineRes)
    }

    @Test fun anUnpairedRadarIsNotReportedAsConnecting() {
        val s = derive(baseInputs(hasBond = false, radarFresh = false, radarConnecting = true))
        assertEquals(R.string.main_status_not_paired_title, s.headlineRes)
    }
}
