// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The dashcam walk-away alarm on the real [RadarLinkCoordinator] when the
 * camera link reports a clean switch-off: the camera counts as off at once,
 * whatever its last read says, until its next completed handshake.
 */
@RunWith(RobolectricTestRunner::class)
class DashcamSwitchOffTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs
    private lateinit var coordinator: RadarLinkCoordinator
    private var now = 1_000_000L

    private var posts = 0
    private var cancels = 0
    private var starts = 0
    private var stops = 0
    private var wakeTicks = 0
    private var rideWakeLockReleases = 0
    private var ridingActivityMs: Long? = null
    private var onPost: () -> Unit = {}
    private val clogs = mutableListOf<String>()
    private val journal = mutableListOf<String>()

    @Before
    fun setUp() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        BatteryStateBus.clearForTest()
        prefs = Prefs(app).apply {
            walkAwayAlarmEnabled = true
            walkAwayAlarmThresholdSec = 15
            dashcamOwnership = DashcamOwnership.YES
            dashcamMac = CAM
        }
        BatteryStateBus.update(BatteryEntry("cam", "Cam", 80, readAtMs = 0L, lastSeenElapsedMs = Long.MIN_VALUE / 2))
        coordinator = RadarLinkCoordinator(
            clock = { now },
            prefs = prefs,
            postWalkAwayNotification = {
                posts++
                onPost()
            },
            cancelWalkAwayNotification = { cancels++ },
            startWalkAwayAlarm = { starts++ },
            stopWalkAwayAlarm = { stops++ },
            alertBeeper = { null },
            clog = { clogs += it },
            journal = { journal += it },
            setReconnectBanner = {},
            resolveDashcamSlug = { "cam" },
            eBikeSnapshot = { null },
            eBikeSnapshotAtMs = { 0L },
            eBikeRidingSinceMs = { null },
            hasLinkableRadar = { true },
            hasEBikeSignal = { false },
            everSawTrack = { true },
            postForgotToLock = {},
            cancelForgotToLock = {},
            cancelWalkAwaySnooze = {},
            clearDashcamBackoff = {},
            lastRidingActivityMs = { ridingActivityMs },
            lastTrackActivityMs = { null },
            clearTrackActivity = {},
            forgetEBikeLock = {},
            wakeTick = { wakeTicks++ },
            acquireRideWakeLock = {},
            releaseRideWakeLock = { rideWakeLockReleases++ },
        )
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        BatteryStateBus.clearForTest()
    }

    private fun heardAt(t: Long) = BatteryStateBus.markSeen("cam", 0L, t)

    /** 61 s of streaming, past the alarm's cold-start grace, then the drop.
     *  Returns the drop instant. */
    private fun radarOffAt(t: Long): Long {
        now = t - 61_000L
        coordinator.markLinkOpen(RADAR)
        coordinator.markConnected()
        now = t
        coordinator.markDisconnected()
        return t
    }

    private fun tickAt(t: Long) {
        now = t
        coordinator.tickWalkAwayState(t, 1_000L)
        coordinator.evaluateWalkAway(t)
    }

    private fun tickEverySecond(from: Long, to: Long) {
        var t = from
        while (t <= to) {
            tickAt(t)
            t += 1_000L
        }
    }

    private fun switchOffAt(t: Long, mac: String = CAM) {
        now = t
        coordinator.markDashcamSwitchedOff(mac)
    }

    private fun clogged(token: String) = clogs.count { it.contains(token) }

    // ── radar off first, camera off shortly after ───────────────────

    @Test
    fun aDashcamSwitchedOffAfterTheRadarDoesNotSoundTheAlarm() {
        val r = radarOffAt(1_000_000L)
        tickEverySecond(r + 1_000L, r + 9_000L)
        heardAt(r + 9_000L)
        switchOffAt(r + 10_000L)
        tickAt(r + 10_000L)
        assertFalse("BLANK at once, not at the stale window", coordinator.snapshot().walkAwayArmed)
        tickEverySecond(r + 11_000L, r + 40_000L)
        assertEquals("the camera was off before the 15 s threshold", 0, posts)
        assertEquals(0, starts)
        assertEquals(1, clogged("# walkaway state=BLANK transition_reason=dashcam-switched-off"))
        assertEquals(0, clogged("dashcam-stale"))
    }

    @Test
    fun aDashcamSwitchedOffBeforeTheRadarStaysSilentWhateverItsLastRead() {
        // The usual pack-up order, with a read recent enough to count as on.
        now = 1_000_000L - 61_000L
        coordinator.markLinkOpen(RADAR)
        coordinator.markConnected()
        heardAt(1_000_000L - 1_000L)
        switchOffAt(1_000_000L - 500L)
        now = 1_000_000L
        coordinator.markDisconnected()
        tickEverySecond(1_000_000L + 1_000L, 1_000_000L + 40_000L)
        assertEquals(0, posts)
        assertEquals(1, clogged("transition_reason=dashcam-switched-off"))
    }

    @Test
    fun switchingTheDashcamOffStopsTheAlarmAtOnce() {
        val r = radarOffAt(1_000_000L)
        heardAt(r + 14_000L)
        tickAt(r + 15_000L)
        assertEquals("sounding: camera heard 1 s ago", 1, posts)
        switchOffAt(r + 17_000L)
        now = r + 18_000L
        coordinator.evaluateWalkAway(r + 18_000L)
        assertEquals(1, cancels)
        assertEquals(1, stops)
        assertNull(coordinator.snapshot().lastWalkAwayFireMs)
        // A read that lands after the switch-off does not bring it back.
        heardAt(r + 19_000L)
        tickEverySecond(r + 20_000L, r + 30_000L)
        assertEquals(1, posts)
        assertEquals(
            listOf(
                "walk-away alarm sounded: dashcam still heard",
                "walk-away alarm: dashcam counted switched off",
                "walk-away alarm stopped: dashcam switched off",
            ),
            journal,
        )
    }

    @Test
    fun aCountedSwitchOffReachesTheJournal() {
        // The capture writer is usually closed by now, so a report of an
        // alarm that did not sound needs this to show why.
        switchOffAt(1_000_000L)
        assertEquals(listOf("walk-away alarm: dashcam counted switched off"), journal)
    }

    @Test
    fun aSwitchOffIgnoredWithBluetoothOffIsNotJournalled() {
        coordinator.markAdapterOff()
        switchOffAt(1_000_000L)
        assertEquals(emptyList<String>(), journal)
    }

    @Test
    fun aDashcamSwitchedBackOnDoesNotReArmTheEpisode() {
        val r = radarOffAt(1_000_000L)
        heardAt(r + 9_000L)
        switchOffAt(r + 10_000L)
        tickAt(r + 10_000L)
        now = r + 12_000L
        coordinator.markDashcamHeard()
        heardAt(r + 13_000L)
        tickAt(r + 16_000L)
        assertEquals(0, posts)
    }

    @Test
    fun aSwitchOffReleasesTheRideWakeLockWithTheBlank() {
        ridingActivityMs = 1_000_000L - 1_000L
        val r = radarOffAt(1_000_000L)
        switchOffAt(r + 1_000L)
        assertEquals(0, rideWakeLockReleases)
        tickAt(r + 2_000L)
        assertEquals(1, rideWakeLockReleases)
    }

    @Test
    fun aSwitchOffDisarmsBeforeTheStaleBoundary() {
        // The sibling of RadarLinkCoordinatorTest.tickDoesNotDisarmAtTheStaleBoundary:
        // at the very instant the stale window has not yet run out, a
        // switched-off camera is already BLANK, and named as such.
        val r = radarOffAt(1_000_000L)
        switchOffAt(r + 1_000L)
        now = r + 20_000L
        coordinator.tickWalkAwayState(r + 20_000L, 1_000L)
        assertFalse(coordinator.snapshot().walkAwayArmed)
        assertEquals(1, clogged("transition_reason=dashcam-switched-off"))
        assertEquals(0, clogged("dashcam-stale"))
    }

    @Test
    fun aSwitchOffWakesTheTick() {
        val before = wakeTicks
        switchOffAt(1_000_000L)
        assertEquals(before + 1, wakeTicks)
    }

    @Test
    fun aSwitchOffWhileRidingSilencesNothing() {
        // Camera first, radar still streaming: nothing is sounding, so the
        // switch-off itself must not reach any stop path.
        now = 1_000_000L
        coordinator.markLinkOpen(RADAR)
        coordinator.markConnected()
        switchOffAt(1_005_000L)
        assertEquals(0, cancels)
        assertEquals(0, stops)
        assertEquals(CAM, coordinator.snapshot().dashcamSwitchedOffMac)
    }

    @Test
    fun anUndoneFireIsNotRecordedAsSounded() {
        val r = radarOffAt(1_000_000L)
        heardAt(r + 14_000L)
        // The radar's first frame lands between the decision and the tone.
        onPost = {
            coordinator.markLinkOpen(RADAR)
            coordinator.markConnected()
        }
        tickAt(r + 15_000L)
        assertEquals(1, posts)
        assertFalse(journal.contains("walk-away alarm sounded: dashcam still heard"))
    }

    // ── what clears the mark, and what it is read for ───────────────────────

    /** The episode from [aDashcamSwitchedOffAfterTheRadarDoesNotSoundTheAlarm]'s
     *  start, with the camera heard right up to the threshold: it sounds unless
     *  the camera counts as switched off. */
    private fun soundsWithTheCameraHeard(): Boolean {
        val r = radarOffAt(now + 600_000L)
        heardAt(r + 14_000L)
        tickAt(r + 15_000L)
        return posts > 0
    }

    @Test
    fun theCamerasNextHandshakeClearsTheSwitchOff() {
        switchOffAt(1_000_000L)
        coordinator.markDashcamHeard()
        assertNull(coordinator.snapshot().dashcamSwitchedOffMac)
        assertTrue(soundsWithTheCameraHeard())
    }

    @Test
    fun theLinkLoopEndingClearsTheSwitchOff() {
        switchOffAt(1_000_000L)
        coordinator.markDashcamLinkGone()
        assertNull(coordinator.snapshot().dashcamSwitchedOffMac)
        assertTrue(soundsWithTheCameraHeard())
    }

    @Test
    fun theRadarStreamingAgainInTheSameRideKeepsTheSwitchOff() {
        // Camera off during a short radar drop, the radar back 20 s later and
        // then switched off itself: the camera is still off.
        val r = radarOffAt(1_000_000L)
        switchOffAt(r + 5_000L)
        now = r + 20_000L
        coordinator.markLinkOpen(RADAR)
        coordinator.markConnected()
        assertEquals(CAM, coordinator.snapshot().dashcamSwitchedOffMac)
        assertFalse(soundsWithTheCameraHeard())
    }

    @Test
    fun aNewRideClearsTheSwitchOff() {
        // Switched off at one ride's end; the next ride's camera link may
        // never complete a handshake, so the mark must not outlive the ride.
        val r = radarOffAt(1_000_000L)
        switchOffAt(r + 5_000L)
        now = r + prefs.radarLongOfflineThresholdMinutes * 60_000L + 60_000L
        coordinator.markLinkOpen(RADAR)
        coordinator.markConnected()
        assertNull(coordinator.snapshot().dashcamSwitchedOffMac)
        assertTrue(soundsWithTheCameraHeard())
    }

    @Test
    fun aSwitchOffAfterTheRiderDismissedAddsNoStoppedLine() {
        val r = radarOffAt(1_000_000L)
        heardAt(r + 14_000L)
        tickAt(r + 15_000L)
        coordinator.markWalkAwayDismissed(snoozed = false)
        switchOffAt(r + 20_000L)
        tickAt(r + 21_000L)
        assertNull("the switched-off dismiss ran", coordinator.snapshot().lastWalkAwayFireMs)
        assertTrue(journal.contains("walk-away alarm dismissed by rider"))
        assertFalse(journal.any { it.startsWith("walk-away alarm stopped") })
    }

    @Test
    fun aCameraGoneUnheardAfterTheRiderDismissedAddsNoStoppedLine() {
        val r = radarOffAt(1_000_000L)
        heardAt(r + 14_000L)
        tickAt(r + 15_000L)
        coordinator.markWalkAwayDismissed(snoozed = false)
        tickAt(r + 40_000L)
        assertEquals("walk-away alarm dismissed by rider", journal.last())
    }

    @Test
    fun anotherCamerasSwitchOffIsIgnored() {
        switchOffAt(1_000_000L, mac = "BB:BB:BB:BB:BB:02")
        assertTrue(soundsWithTheCameraHeard())
    }

    @Test
    fun theSelectedCameraInAnotherCaseIsStillItself() {
        switchOffAt(1_000_000L, mac = CAM.lowercase())
        assertFalse(soundsWithTheCameraHeard())
    }

    @Test
    fun aSwitchOffReportedWithBluetoothOffIsIgnored() {
        // Bluetooth's own teardown ends the camera link too.
        coordinator.markAdapterOff()
        switchOffAt(1_000_000L)
        coordinator.markAdapterOn()
        assertNull(coordinator.snapshot().dashcamSwitchedOffMac)
        assertTrue(soundsWithTheCameraHeard())
    }

    @Test
    fun aCameraNoLongerHeardIsRecordedAsTheReasonItStopped() {
        val r = radarOffAt(1_000_000L)
        heardAt(r + 14_000L)
        tickAt(r + 15_000L)
        tickAt(r + 35_000L)
        assertEquals(1, stops)
        assertEquals("walk-away alarm stopped: dashcam no longer heard", journal.last())
    }

    @Test
    fun theTimeLimitOnlyFollowsADismissAndAddsNoLine() {
        // Heard throughout: undismissed it sounds again at 5 min, so the
        // 10 min limit is reached only once the rider has dismissed it.
        val r = radarOffAt(1_000_000L)
        var t = r + 15_000L
        while (t <= r + 320_000L) {
            heardAt(t - 1_000L)
            tickAt(t)
            t += 5_000L
        }
        assertEquals("sounded again at the rate limit", 2, posts)
        coordinator.markWalkAwayDismissed(snoozed = false)
        val linesBefore = journal.size
        val stopsBefore = stops
        while (t <= r + 1_000_000L) {
            heardAt(t - 1_000L)
            tickAt(t)
            t += 5_000L
        }
        assertEquals("the time limit stopped it once more", stopsBefore + 1, stops)
        assertNull(coordinator.snapshot().lastWalkAwayFireMs)
        assertEquals(linesBefore, journal.size)
    }

    private companion object {
        const val RADAR = "AA:AA:AA:AA:AA:01"
        const val CAM = "CC:CC:CC:CC:CC:01"
    }
}
