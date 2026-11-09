// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.DashcamProbeHarness.Companion.DROP_MS
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The dashcam walk-away alarm against the probe's real schedule
 * ([DashcamProbeHarness]), with reads at the code's own limits rather than at
 * any measured read time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DashcamHeardWindowTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = clear()

    @After
    fun tearDown() = clear()

    private fun clear() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(PREFS_THROTTLE, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(DashcamProbeHarness.KNOWN_DEVICES, Context.MODE_PRIVATE).edit().clear().commit()
        BatteryStateBus.clearForTest()
    }

    // ── a camera left on, every read answering ──────────────────────────────

    @Test
    fun atFifteenSecondsACameraLeftOnSoundsAndIsNotCutShort() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L)
        h.runUntil(DROP_MS + 120_000L)
        assertEquals(1, h.posts)
        assertEquals(0, h.stops)
        assertTrue(h.coordinator.snapshot().walkAwayArmed)
        assertEquals(0, h.clogged("dashcam-stale"))
    }

    @Test
    fun atThirtySecondsACameraLeftOnSounds() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 30, readMs = 7_999L, probePhaseMs = 1_000L)
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(1, h.posts)
        assertEquals(0, h.stops)
    }

    @Test
    fun atTwoMinutesACameraLeftOnSoundsAtTheThreshold() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 120, readMs = 14_999L)
        h.runUntil(DROP_MS + 118_000L)
        assertEquals(0, h.posts)
        h.runUntil(DROP_MS + 120_000L)
        assertEquals(listOf(DROP_MS + 120_000L), h.postTimes)
    }

    /** Every probe phase, read times either side of a tick and up to the
     *  timeout, and the slider's ends and middle: the alarm sounds within two
     *  ticks of the threshold, is never cut short, and sounds again at the
     *  5 min rate limit. */
    @Test
    fun aCameraLeftOnSoundsAtTheThresholdWhateverTheReadTimeAndPhase() {
        val failures = mutableListOf<String>()
        for (thresholdSec in listOf(15, 30, 45, 120)) {
            for (readMs in listOf(100L, 1_999L, 2_001L, 7_999L, 14_999L)) {
                for (phase in 0L until 2_000L step 100L) {
                    runTest {
                        val h = DashcamProbeHarness(this, app, thresholdSec, readMs, probePhaseMs = phase)
                        val due = DROP_MS + thresholdSec * 1_000L
                        h.runUntil(due + 306_000L)
                        val first = h.postTimes.firstOrNull()
                        val ok = first != null &&
                            first >= due &&
                            first < due + 4_000L &&
                            h.postTimes.size == 2 &&
                            h.postTimes[1] - first in 300_000L until 302_000L &&
                            h.stops == 0 &&
                            h.clogged("dashcam-stale") == 0
                        if (!ok) failures += "threshold=$thresholdSec read=$readMs phase=$phase posts=${h.postTimes.map { it - DROP_MS }} stops=${h.stops}"
                    }
                    clear()
                }
            }
        }
        assertEquals("cases that went quiet or fired late:\n" + failures.joinToString("\n"), 0, failures.size)
    }

    // ── which camera the radar-left-on alarm names ──────────────────────────

    private fun bothLeftWithTheCameraLastHeard(beforeDropMs: Long) = run {
        var named = false
        runTest {
            val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { false })
            h.runUntil(DROP_MS - beforeDropMs)
            h.cameraAnswersNow()
            h.runUntil(DROP_MS)
            named = h.coordinator.snapshot().radarLeftOnBothLeft
        }
        clear()
        named
    }

    @Test
    fun aCameraHeardWithinOneProbeCycleOfTheDropIsNamed() {
        // Read at 20 s of age on a 2 s tick, answering by the 15 s timeout.
        assertTrue(bothLeftWithTheCameraLastHeard(37_000L))
        assertFalse(bothLeftWithTheCameraLastHeard(37_001L))
    }

    // ── what keeps it going, and what ends it ───────────────────────────────

    @Test
    fun oneFastFailedReadDoesNotLoseTheAlarm() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 500L, answers = { it !in DROP_MS until DROP_MS + 1_000L })
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(listOf(DROP_MS + 16_000L), h.postTimes)
        assertEquals(0, h.stops)
    }

    @Test
    fun aFailingRadarHoldingItsLinkOpenDoesNotSlowTheArmedProbe() = runTest {
        // The probe backs off while a radar link is open; with no failed read,
        // from the armed base.
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L)
        h.runUntil(DROP_MS + 1_000L)
        h.coordinator.markLinkOpen(DashcamProbeHarness.RADAR)
        h.runUntil(DROP_MS + 100_000L)
        assertEquals(1, h.posts)
        assertEquals(0, h.stops)
        assertEquals(0, h.clogged("dashcam-stale"))
    }

    @Test
    fun aSlowHomeAssistantDoesNotCutTheAlarmShort() = runTest {
        // Each read's publish takes two HA timeouts.
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, publishMs = 6_000L)
        h.runUntil(DROP_MS + 120_000L)
        assertEquals(1, h.posts)
        assertEquals(0, h.stops)
    }

    @Test
    fun aCameraSwitchedOffAfterItSoundedStopsItWithinTheWindow() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { false })
        h.runUntil(DROP_MS + 5_000L)
        h.cameraAnswersNow()
        h.runUntil(DROP_MS + 24_000L)
        assertEquals(listOf(DROP_MS + 16_000L), h.postTimes)
        assertEquals(0, h.stops)
        h.runUntil(DROP_MS + 26_000L)
        assertEquals(1, h.stops)
        assertEquals("walk-away alarm stopped: dashcam no longer heard", h.journal.last())
    }

    @Test
    fun aCameraOnlyTheProbeHearsEndsSoonAfterTheMonitoringWindow() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.runUntil(DROP_MS + 899_000L)
        assertEquals(0, h.stops)
        h.runUntil(DROP_MS + 1_200_000L)
        assertEquals(1, h.stops)
        assertTrue(h.postTimes.all { it < DROP_MS + 920_000L })
        assertFalse(h.coordinator.snapshot().walkAwayArmed)
    }

    @Test
    fun aSnoozeEndsWithTheAlarmForACameraStillOn() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L)
        h.runUntil(DROP_MS + 20_000L)
        h.coordinator.markWalkAwayDismissed(snoozed = true)
        h.runUntil(DROP_MS + 140_000L)
        h.coordinator.clearWalkAwayDismissalForReArm()
        h.runUntil(DROP_MS + 150_000L)
        assertEquals(2, h.posts)
        assertTrue(h.postTimes[1] in DROP_MS + 140_000L until DROP_MS + 144_000L)
    }

    @Test
    fun aDismissEndsTheArmedProbing() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.runUntil(DROP_MS + 20_000L)
        h.coordinator.markWalkAwayDismissed(snoozed = false)
        h.runUntil(DROP_MS + 60_000L)
        val before = h.launches.size
        h.runUntil(DROP_MS + 120_000L)
        // Back on the 20 s cadence: three reads in a minute, not fifteen.
        assertTrue("launches=${h.launches.size - before}", h.launches.size - before <= 3)
        assertEquals(1, h.posts)
    }

    @Test
    fun aDismissInOneEpisodeLeavesTheNextArmedProbing() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.runUntil(DROP_MS + 20_000L)
        h.coordinator.markWalkAwayDismissed(snoozed = false)
        h.runUntil(DROP_MS + 40_000L)
        h.coordinator.markLinkOpen(DashcamProbeHarness.RADAR)
        h.coordinator.markConnected()
        h.runUntil(DROP_MS + 110_000L)
        h.coordinator.markDisconnected()
        val before = h.launches.size
        h.runUntil(DROP_MS + 170_000L)
        assertTrue("launches=${h.launches.size - before}", h.launches.size - before >= 10)
    }

    @Test
    fun withTheAlarmOffTheProbeKeepsItsRideCadence() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.prefs.walkAwayAlarmEnabled = false
        h.runUntil(DROP_MS + 60_000L)
        val afterDrop = h.launches.count { it >= DROP_MS }
        assertTrue("launches=$afterDrop", afterDrop <= 3)
    }

    // ── the walk-away wakelock ──────────────────────────────────────────────

    @Test
    fun anArmedDropHoldsTheWalkAwayWakeLockUntilItSounds() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 30, readMs = 100L)
        h.runUntil(DROP_MS)
        assertTrue(h.walkAwayWakeLockHeld)
        assertEquals(45_000L, h.walkAwayWakeLockCapMs)
        h.runUntil(DROP_MS + 28_000L)
        assertTrue(h.walkAwayWakeLockHeld)
        h.runUntil(DROP_MS + 30_000L)
        assertEquals(1, h.posts)
        assertFalse(h.walkAwayWakeLockHeld)
    }

    @Test
    fun theWalkAwayWakeLockGoesWithTheBlank() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { false })
        h.runUntil(DROP_MS + 20_000L)
        assertTrue(h.walkAwayWakeLockHeld)
        h.runUntil(DROP_MS + 22_000L)
        assertFalse(h.walkAwayWakeLockHeld)
    }

    @Test
    fun theWalkAwayWakeLockGoesWithADismissOrTheRadarBack() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 30, readMs = 100L)
        h.runUntil(DROP_MS + 4_000L)
        h.coordinator.markWalkAwayDismissed(snoozed = true)
        assertFalse(h.walkAwayWakeLockHeld)
        h.walkAwayWakeLockHeld = true
        h.coordinator.markLinkOpen(DashcamProbeHarness.RADAR)
        h.coordinator.markConnected()
        assertFalse(h.walkAwayWakeLockHeld)
    }

    @Test
    fun noWalkAwayWakeLockWithTheAlarmOff() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 30, readMs = 100L)
        h.prefs.walkAwayAlarmEnabled = false
        h.runUntil(DROP_MS + 2_000L)
        assertTrue(h.coordinator.snapshot().walkAwayArmed)
        assertFalse(h.walkAwayWakeLockHeld)
        assertEquals(null, h.walkAwayWakeLockCapMs)
    }

    // ── a camera that stops answering ───────────────────────────────────────

    @Test
    fun aCameraThatStopsAnsweringBeforeTheThresholdIsNeverAnnounced() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 30, readMs = 14_999L, answers = { false })
        h.runUntil(DROP_MS + 9_000L)
        h.cameraAnswersNow()
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(0, h.posts)
    }

    @Test
    fun aCameraThatNeverAnswersGoesBlankAtTheStaleWindow() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { false })
        h.ridingActivityMs = DROP_MS - 60_000L
        h.runUntil(DROP_MS + 20_000L)
        assertEquals(0, h.rideWakeLockReleases)
        h.runUntil(DROP_MS + 22_000L)
        assertFalse(h.coordinator.snapshot().walkAwayArmed)
        assertEquals(1, h.rideWakeLockReleases)
        assertEquals(1, h.clogged("transition_reason=dashcam-stale"))
        assertEquals(0, h.posts)
        assertEquals(1, h.journal.count { it == "walk-away alarm disarmed: dashcam not heard" })
    }

    @Test
    fun aFastReadThenOneAtTheTimeoutKeepsItSounding() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 30, readMs = 0L, readMsFor = { if (it % 2 == 0) 2_001L else 14_999L })
        h.runUntil(DROP_MS + 120_000L)
        assertEquals(1, h.posts)
        assertTrue(h.postTimes[0] in DROP_MS + 30_000L until DROP_MS + 34_000L)
        assertEquals(0, h.stops)
        assertEquals(0, h.clogged("dashcam-stale"))
    }

    // ── a phone that slept ──────────────────────────────────────────────────

    @Test
    fun aLateWakeAfterTheAlarmSoundedDoesNotEndIt() = runTest {
        // Both loops asleep for 200 s with the camera on: the first tick
        // after the wake waits for a read, then it repeats on time.
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.runUntil(DROP_MS + 20_000L)
        assertEquals(listOf(DROP_MS + 16_000L), h.postTimes)
        h.dozeUntil(DROP_MS + 219_000L)
        h.runUntil(DROP_MS + 330_000L)
        assertEquals(0, h.stops)
        assertTrue(h.coordinator.snapshot().walkAwayArmed)
        assertEquals(listOf(DROP_MS + 16_000L, DROP_MS + 316_000L), h.postTimes)
    }

    @Test
    fun aLateWakeDuringASnoozeStillSoundsAfterIt() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.runUntil(DROP_MS + 18_000L)
        h.coordinator.markWalkAwayDismissed(snoozed = true)
        h.dozeUntil(DROP_MS + 219_000L)
        // The snooze's own timer, run as the phone wakes.
        h.coordinator.clearWalkAwayDismissalForReArm()
        h.runUntil(DROP_MS + 230_000L)
        assertTrue(h.coordinator.snapshot().walkAwayArmed)
        assertEquals(listOf(DROP_MS + 16_000L, DROP_MS + 222_000L), h.postTimes)
    }

    private fun armedAfterATickThisLate(elapsedMs: Long) = run {
        var armed = false
        runTest {
            val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { false })
            h.runUntil(DROP_MS)
            h.dozeUntil(DROP_MS + 21_000L)
            h.coordinator.tickWalkAwayState(h.now(), elapsedMs)
            armed = h.coordinator.snapshot().walkAwayArmed
        }
        clear()
        armed
    }

    @Test
    fun aTickMoreThanTwoTicksAfterTheLastIsAWake() {
        assertFalse(armedAfterATickThisLate(4_000L))
        assertTrue(armedAfterATickThisLate(4_001L))
    }

    /** Both loops asleep [sleepMs] at a time from [fromMs] to [untilMs], each
     *  wake one tick of both. */
    private fun DashcamProbeHarness.dozeInSpells(fromMs: Long, untilMs: Long, sleepMs: Long) {
        var t = fromMs
        while (t < untilMs) {
            dozeUntil(t + sleepMs - 1_000L)
            runUntil(t + sleepMs)
            t += sleepMs
        }
    }

    @Test
    fun repeatedShortWakesDoNotHoldOffTheStopOfACameraThatWentOff() = runTest {
        // The camera stops answering at R+30 s and the phone wakes every 6 s:
        // only the first wake counts, so it stops 20 s on from it, not never.
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L, answers = { it < DROP_MS + 30_000L })
        h.runUntil(DROP_MS + 30_000L)
        assertEquals(listOf(DROP_MS + 16_000L), h.postTimes)
        h.dozeInSpells(DROP_MS + 30_000L, DROP_MS + 120_000L, 6_000L)
        assertEquals(listOf(DROP_MS + 60_000L), h.stopTimes)
        assertFalse(h.coordinator.snapshot().walkAwayArmed)
    }

    @Test
    fun aCameraStillAnsweringAcrossRepeatedLongSleepsSoundsItsRepeat() = runTest {
        // Each 30 s sleep is a wake after a hearing, so each one counts. A wake
        // sees a hearing 30 s old, too old to fire on, but the read it starts
        // runs the tick as the camera answers, and the repeat sounds then.
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.ticksOnKick = true
        h.runUntil(DROP_MS + 20_000L)
        h.dozeInSpells(DROP_MS + 20_000L, DROP_MS + 350_000L, 30_000L)
        assertEquals(0, h.stops)
        assertTrue(h.coordinator.snapshot().walkAwayArmed)
        assertEquals(listOf(DROP_MS + 16_000L, DROP_MS + 320_100L), h.postTimes)
    }

    @Test
    fun anAnswerWakesTheTickOnlyWhileTheAlarmCanSound() = runTest {
        // The ride's reads answer with the radar streaming, and a drop in the
        // radar's first minute arms an alarm that cannot sound: no answer
        // wakes the tick in either, so the loop keeps its own cadence.
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.runUntil(DROP_MS - 30_000L)
        // The stream restarts, so 30 s have streamed this session at the drop.
        h.coordinator.markConnected()
        h.runUntil(DROP_MS - 2_000L)
        assertTrue("launches=${h.launches.size}", h.launches.size >= 2)
        assertEquals(emptyList<Long>(), h.kickTimes)
        h.runUntil(DROP_MS + 60_000L)
        assertTrue("launches=${h.launches.count { it >= DROP_MS }}", h.launches.count { it >= DROP_MS } >= 1)
        // The drop's own wake, and no answer's.
        assertEquals(listOf(DROP_MS), h.kickTimes)
    }

    // ── when it can no longer sound ─────────────────────────────────────────

    @Test
    fun theRadarLeftOnAlarmSoundingEndsTheArmedProbingAndTheLock() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 30, readMs = 100L)
        h.prefs.setRadarLeftOnSwitchOffs(DashcamProbeHarness.RADAR, 3)
        h.dropLoss = LinkLoss(gapMs = 3_000L, status = 147)
        h.runUntil(DROP_MS + 10_000L)
        // While its check is pending, the dashcam alarm may still sound after it.
        assertTrue(h.walkAwayWakeLockHeld)
        // Every 4 s: a read ends 0.1 s in, the next starts 2 s after it.
        assertEquals(3, h.launches.count { it >= DROP_MS })
        h.runUntil(DROP_MS + 22_000L)
        assertEquals(1, h.journal.count { it == "walk-away alarm sounded: radar and dashcam left on" })
        assertFalse(h.walkAwayWakeLockHeld)
        val before = h.launches.size
        h.runUntil(DROP_MS + 82_000L)
        assertTrue("launches=${h.launches.size - before}", h.launches.size - before <= 3)
        assertEquals(0, h.posts)
    }

    @Test
    fun aDropInTheRadarsFirstMinuteTakesNoLockAndNoArmedProbing() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.runUntil(DROP_MS - 30_000L)
        // The stream restarts, so 30 s have streamed this session at the drop.
        h.coordinator.markConnected()
        h.runUntil(DROP_MS)
        assertTrue(h.coordinator.snapshot().walkAwayArmed)
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(null, h.walkAwayWakeLockCapMs)
        assertTrue("launches=${h.launches.count { it >= DROP_MS }}", h.launches.count { it >= DROP_MS } <= 3)
        assertEquals(0, h.posts)
        assertEquals("walk-away alarm armed but cannot sound", h.journal.first())
    }

    @Test
    fun bluetoothOffAfterTheDropKeepsItQuiet() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { false })
        h.runUntil(DROP_MS - 2_000L)
        h.cameraAnswersNow()
        h.runUntil(DROP_MS + 1_000L)
        assertTrue(h.walkAwayWakeLockHeld)
        h.coordinator.markAdapterOff()
        assertFalse(h.walkAwayWakeLockHeld)
        h.runUntil(DROP_MS + 30_000L)
        assertEquals(0, h.posts)
    }

    @Test
    fun aDropWithBluetoothAlreadyOffTakesNoLockAndNoArmedProbing() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.runUntil(DROP_MS - 2_000L)
        h.coordinator.markAdapterOff()
        h.runUntil(DROP_MS + 10_000L)
        assertEquals(null, h.walkAwayWakeLockCapMs)
        assertTrue("launches=${h.launches.count { it >= DROP_MS }}", h.launches.count { it >= DROP_MS } <= 1)
        assertTrue(h.journal.contains("walk-away alarm armed but cannot sound"))
    }

    @Test
    fun bluetoothOffWhileItSoundsStillLetsItStop() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L, answers = { it < DROP_MS + 17_000L })
        h.runUntil(DROP_MS + 18_000L)
        assertEquals(1, h.posts)
        h.coordinator.markAdapterOff()
        h.runUntil(DROP_MS + 40_000L)
        assertEquals(1, h.stops)
        assertEquals(1, h.posts)
    }

    // ── which camera the drop names, with sightings in between ──────────────

    @Test
    fun sightingsDoNotHoldOffTheReadsThatNameTheCamera() = runTest {
        // A sighting refreshes what the screen shows, with no contact; the
        // ride's reads go on regardless, so a camera left on is named.
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        var t = DashcamProbeHarness.CONNECT_MS + 5_000L
        while (t < DROP_MS) {
            h.runUntil(t)
            BatteryStateBus.markSeen("cam", t, t)
            t += 5_000L
        }
        h.runUntil(DROP_MS)
        assertTrue(h.coordinator.snapshot().radarLeftOnBothLeft)
    }

    // ── the probe's own bookkeeping ─────────────────────────────────────────

    @Test
    fun aLaunchSkippedForAReadInFlightIsNotCountedAsAProbe() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L)
        h.runUntil(DROP_MS + 10_000L)
        assertEquals(h.launches.last(), h.lastProbeMs[DashcamProbeHarness.CAM])
    }

    @Test
    fun theRadarBackAsTheLockIsTakenLeavesItReleased() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.onWalkAwayWakeLockAcquire = {
            h.coordinator.markLinkOpen(DashcamProbeHarness.RADAR)
            h.coordinator.markConnected()
        }
        h.runUntil(DROP_MS)
        assertFalse(h.walkAwayWakeLockHeld)
    }

    @Test
    fun aSwitchOffBlankAsTheLockIsTakenLeavesItReleased() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.onWalkAwayWakeLockAcquire = {
            h.coordinator.markDashcamSwitchedOff(DashcamProbeHarness.CAM)
            h.coordinator.tickWalkAwayState(h.now(), DashcamProbeHarness.WALK_AWAY_TICK_MS)
        }
        h.runUntil(DROP_MS)
        assertFalse(h.walkAwayWakeLockHeld)
    }

    @Test
    fun bluetoothOffAsTheLockIsTakenLeavesItReleased() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.onWalkAwayWakeLockAcquire = { h.coordinator.markAdapterOff() }
        h.runUntil(DROP_MS)
        assertFalse(h.walkAwayWakeLockHeld)
    }

    private fun lockTakenAfterStreaming(streamedMs: Long) = run {
        var taken = false
        runTest {
            val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
            h.runUntil(DROP_MS - streamedMs)
            // The stream restarts, so this much has streamed this session at the drop.
            h.coordinator.markConnected()
            h.runUntil(DROP_MS)
            taken = h.walkAwayWakeLockCapMs != null
        }
        clear()
        taken
    }

    @Test
    fun theLockNeedsTheRadarsFirstMinuteStreamed() {
        assertTrue(lockTakenAfterStreaming(60_000L))
        assertFalse(lockTakenAfterStreaming(59_999L))
    }

    // ── the journal ─────────────────────────────────────────────────────────

    @Test
    fun anArmedDropSaysSoInTheJournal() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.runUntil(DROP_MS + 2_000L)
        assertEquals(listOf("walk-away alarm armed for the dashcam"), h.journal)
    }

    @Test
    fun noArmingLinesWithTheAlarmOffOrNoDashcamInUse() {
        // Each kind of drop: armed and then unheard, one while riding, one
        // with the eBike unlocked.
        val drops = listOf<(DashcamProbeHarness) -> Unit>(
            {},
            { it.ridingActivityMs = DROP_MS - 10_000L },
            {
                it.eBike = LiveDataSnapshot(systemLocked = false)
                it.eBikeAtMs = DROP_MS - 1_000L
            },
        )
        for (setUp in listOf<(Prefs) -> Unit>({ it.walkAwayAlarmEnabled = false }, { it.dashcamOwnership = DashcamOwnership.NO })) {
            for (drop in drops) {
                runTest {
                    val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { false })
                    setUp(h.prefs)
                    drop(h)
                    h.runUntil(DROP_MS + 30_000L)
                    assertFalse(h.coordinator.snapshot().walkAwayArmed)
                    assertEquals(emptyList<String>(), h.journal)
                }
                clear()
            }
        }
    }

    @Test
    fun aSwitchOffIsNotLoggedAsUnheard() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.runUntil(DROP_MS + 2_000L)
        h.coordinator.markDashcamSwitchedOff(DashcamProbeHarness.CAM)
        h.runUntil(DROP_MS + 30_000L)
        assertFalse(h.coordinator.snapshot().walkAwayArmed)
        assertEquals(0, h.journal.count { it == "walk-away alarm disarmed: dashcam not heard" })
    }

    // ── no dashcam in use ───────────────────────────────────────────────────

    @Test
    fun noWalkAwayWakeLockWithoutADashcamInUse() {
        for (setUp in listOf<(Prefs) -> Unit>({ it.dashcamOwnership = DashcamOwnership.NO }, { it.dashcamMac = null })) {
            runTest {
                val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
                setUp(h.prefs)
                h.runUntil(DROP_MS + 2_000L)
                assertTrue(h.coordinator.snapshot().walkAwayArmed)
                assertEquals(null, h.walkAwayWakeLockCapMs)
            }
            clear()
        }
    }
}
