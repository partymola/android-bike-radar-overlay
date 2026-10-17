// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The radar-left-on alarm on the real [RadarLinkCoordinator]: which drops it
 * sounds for, once, 20 s after, and what keeps it silent. The rules
 * themselves are [RadarLeftOnDeciderTest]'s; this pins that each input
 * reaches them from the state it must come from, sampled at the drop.
 */
@RunWith(RobolectricTestRunner::class)
class RadarLeftOnAlarmTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs
    private lateinit var coordinator: RadarLinkCoordinator
    private var now = 1_000_000L

    private val posted = mutableListOf<WalkAwayAlarmKind>()
    private var dashcamPosts = 0
    private var starts = 0
    private var stops = 0
    private var cancels = 0
    private var wakeLockHeld = false
    private var wakeLockAcquires = 0
    private val journal = mutableListOf<String>()

    private var ridingActivityMs: Long? = null
    private var ridingSinceMs: Long? = null
    private var ebike: LiveDataSnapshot? = null
    private var batteryPct: Int? = 60
    private var dashcamSlug: String? = null
    private var onPost: () -> Unit = {}

    @Before
    fun setUp() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        BatteryStateBus.clearForTest()
        prefs = Prefs(app)
        coordinator = RadarLinkCoordinator(
            clock = { now },
            prefs = prefs,
            postWalkAwayNotification = { dashcamPosts++ },
            cancelWalkAwayNotification = { cancels++ },
            startWalkAwayAlarm = { starts++ },
            stopWalkAwayAlarm = { stops++ },
            alertBeeper = { null },
            clog = {},
            journal = { journal += it },
            setReconnectBanner = {},
            resolveDashcamSlug = { dashcamSlug },
            eBikeSnapshot = { ebike },
            eBikeSnapshotAtMs = { now },
            eBikeRidingSinceMs = { ridingSinceMs },
            hasLinkableRadar = { true },
            hasEBikeSignal = { ebike != null },
            everSawTrack = { false },
            postForgotToLock = {},
            cancelForgotToLock = {},
            cancelWalkAwaySnooze = {},
            clearDashcamBackoff = {},
            lastRidingActivityMs = { ridingActivityMs },
            lastTrackActivityMs = { null },
            clearTrackActivity = {},
            forgetEBikeLock = {},
            wakeTick = {},
            acquireRideWakeLock = {},
            releaseRideWakeLock = {},
            radarBatteryPct = { mac -> batteryPct.takeIf { mac.equals(RADAR_A, ignoreCase = true) || mac == RADAR_B } },
            postRadarLeftOnNotification = { kind ->
                posted += kind
                onPost()
            },
            acquireRadarLeftOnWakeLock = {
                wakeLockHeld = true
                wakeLockAcquires++
            },
            releaseRadarLeftOnWakeLock = { wakeLockHeld = false },
        )
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        BatteryStateBus.clearForTest()
    }

    private fun stream(mac: String = RADAR_A) {
        coordinator.markLinkOpen(mac)
        coordinator.markConnected()
    }

    /** A drop at [now] the radar link measured as [gapMs] with [status]. */
    private fun drop(gapMs: Long, status: Int = 147) = coordinator.markDisconnected(LinkLoss(gapMs, status))

    private fun switchOffs(n: Int, mac: String = RADAR_A) {
        repeat(n) {
            stream(mac)
            now += 600_000L
            drop(300L, status = 257)
            now += 60_000L
        }
    }

    /** A calibrated radar left on: streamed, then lost 3.5 s after its last frame. */
    private fun walkAway(mac: String = RADAR_A) {
        stream(mac)
        now += 600_000L
        drop(3_500L)
    }

    private fun tickAt(msAfterDrop: Long, dropAt: Long) {
        now = dropAt + msAfterDrop
        coordinator.evaluateRadarLeftOn(now)
    }

    @Test
    fun aWalkAwayFromACalibratedRadarSoundsOnceTwentySecondsLater() {
        switchOffs(3)
        walkAway()
        val dropAt = now
        assertTrue("held through the check", wakeLockHeld)
        tickAt(19_999L, dropAt)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
        tickAt(20_000L, dropAt)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
        assertEquals(1, starts)
        assertFalse("released once it has sounded", wakeLockHeld)
        tickAt(22_000L, dropAt)
        tickAt(400_000L, dropAt)
        assertEquals("once per off-episode", 1, posted.size)
        assertTrue(journal.contains("walk-away alarm sounded: radar left on"))
        assertEquals("the dashcam alarm did not sound", 0, dashcamPosts)
    }

    @Test
    fun aRadarThatHasNotShownThreeSwitchOffsStaysSilentAndLearns() {
        switchOffs(2)
        assertEquals(listOf("(1 of 3)", "(2 of 3)"), journal.map { it.substringAfter("switch-off ") })
        walkAway()
        val dropAt = now
        assertFalse(wakeLockHeld)
        tickAt(20_000L, dropAt)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
        // The third clean switch-off arms the next walk-away.
        now += 60_000L
        switchOffs(1)
        walkAway()
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
    }

    @Test
    fun anotherRadarStartsCalibrationAfresh() {
        switchOffs(3, RADAR_A)
        walkAway(RADAR_B)
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
        // One radar is remembered: the other's first switch-off replaces it.
        now += 60_000L
        switchOffs(1, RADAR_B)
        assertEquals(1, prefs.radarLeftOnSwitchOffs(RADAR_B))
        assertEquals(0, prefs.radarLeftOnSwitchOffs(RADAR_A))
    }

    @Test
    fun theSameRadarInAnotherCaseKeepsItsCount() {
        switchOffs(3, RADAR_A)
        walkAway(RADAR_A.lowercase())
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
    }

    @Test
    fun aSwitchOffOrAnUnclearDropNeverSounds() {
        switchOffs(3)
        stream()
        now += 600_000L
        drop(300L, status = 257)
        tickAt(20_000L, now)
        stream()
        now += 600_000L
        drop(1_500L)
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
        assertEquals(0, wakeLockAcquires)
    }

    @Test
    fun anEndingThePhoneProducedNeitherSoundsNorCalibrates() {
        repeat(3) {
            stream()
            now += 600_000L
            drop(300L, status = 0)
            now += 60_000L
        }
        assertEquals(0, prefs.radarLeftOnSwitchOffs(RADAR_A))
    }

    @Test
    fun ridingJustBeforeTheDropSilencesIt() {
        switchOffs(3)
        stream()
        now += 600_000L
        ridingActivityMs = now - 10_000L
        drop(3_500L)
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
    }

    @Test
    fun anEBikeRidingRunStillOpenDoesNotSilenceIt() {
        // The run stays open for about 110 s after the last movement, and a
        // rider who locks up and walks off is well inside that.
        switchOffs(3)
        ridingSinceMs = 1_000L
        walkAway()
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
    }

    @Test
    fun aFreshlyUnlockedBikeSilencesIt() {
        switchOffs(3)
        ebike = LiveDataSnapshot(systemLocked = false)
        walkAway()
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
    }

    @Test
    fun aNearlyFlatRadarSilencesIt() {
        switchOffs(3)
        batteryPct = 9
        walkAway()
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
        batteryPct = 10
        now += 60_000L
        walkAway()
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
    }

    @Test
    fun theRadarComingBackEndsTheCheck() {
        switchOffs(3)
        walkAway()
        val dropAt = now
        now = dropAt + 10_000L
        stream()
        assertFalse(wakeLockHeld)
        tickAt(20_000L, dropAt)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
    }

    @Test
    fun bluetoothGoingOffSilencesIt() {
        switchOffs(3)
        walkAway()
        coordinator.markAdapterOff()
        assertFalse(wakeLockHeld)
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
    }

    @Test
    fun aDropWhileBluetoothIsOffStartsNoCheck() {
        switchOffs(3)
        coordinator.markAdapterOff()
        walkAway()
        assertEquals(0, wakeLockAcquires)
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
        // Back on, the next episode is judged afresh.
        coordinator.markAdapterOn()
        now += 60_000L
        walkAway()
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
    }

    @Test
    fun aCameraHeardJustBeforeTheDropIsNamedToo() {
        switchOffs(3)
        dashcamSlug = "cam"
        BatteryStateBus.update(BatteryEntry("cam", "Cam", 80))
        stream()
        now += 600_000L
        BatteryStateBus.markSeen("cam", 0L, now - 20_000L)
        drop(3_500L)
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR_AND_DASHCAM), posted)
        assertTrue(journal.contains("walk-away alarm sounded: radar and dashcam left on"))
    }

    @Test
    fun aCameraLastHeardEarlierIsNotNamed() {
        switchOffs(3)
        dashcamSlug = "cam"
        BatteryStateBus.update(BatteryEntry("cam", "Cam", 80))
        stream()
        now += 600_000L
        BatteryStateBus.markSeen("cam", 0L, now - 20_001L)
        drop(3_500L)
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
    }

    @Test
    fun aDismissEndsItForTheEpisode() {
        switchOffs(3)
        walkAway()
        val dropAt = now
        tickAt(20_000L, dropAt)
        coordinator.markWalkAwayDismissed(snoozed = false)
        tickAt(200_000L, dropAt)
        assertEquals(1, posted.size)
    }

    @Test
    fun aSnoozeGivesTheRadarLeftOnAlarmOneMoreFire() {
        switchOffs(3)
        walkAway()
        val dropAt = now
        tickAt(20_000L, dropAt)
        coordinator.markWalkAwayDismissed(snoozed = true)
        tickAt(60_000L, dropAt)
        assertEquals("silent while snoozed", 1, posted.size)
        coordinator.clearWalkAwayDismissalForReArm()
        tickAt(142_000L, dropAt)
        assertEquals(2, posted.size)
        tickAt(144_000L, dropAt)
        tickAt(400_000L, dropAt)
        assertEquals("one more, not a repeat", 2, posted.size)
    }

    @Test
    fun theSwitchOffSilencesItButCalibrationStillCounts() {
        prefs.radarLeftOnAlarmEnabled = false
        switchOffs(3)
        assertEquals(3, prefs.radarLeftOnSwitchOffs(RADAR_A))
        walkAway()
        assertEquals(0, wakeLockAcquires)
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
    }

    @Test
    fun aReconnectAsItSoundsLeavesItStopped() {
        switchOffs(3)
        walkAway()
        onPost = { stream() }
        tickAt(20_000L, now)
        assertEquals(1, posted.size)
        assertTrue("cancelled after the post", cancels >= 2)
        assertTrue("stopped after the start", stops >= 2)
        assertFalse(journal.contains("walk-away alarm sounded: radar left on"))
    }

    @Test
    fun aDismissAsItSoundsLeavesItStopped() {
        switchOffs(3)
        walkAway()
        val stopsBefore = stops
        onPost = { coordinator.markWalkAwayDismissed(snoozed = false) }
        tickAt(20_000L, now)
        assertEquals(stopsBefore + 1, stops)
    }

    /** The dashcam alarm armed and due: camera heard, threshold passed. */
    private fun dashcamAlarmDue() {
        prefs.dashcamWarnWhenOff = true
        prefs.walkAwayAlarmThresholdSec = 15
        dashcamSlug = "cam"
        BatteryStateBus.update(BatteryEntry("cam", "Cam", 80))
    }

    @Test
    fun theRadarLeftOnAlarmKeepsTheDashcamAlarmSilent() {
        switchOffs(3)
        dashcamAlarmDue()
        stream()
        now += 600_000L
        BatteryStateBus.markSeen("cam", 0L, now - 1_000L)
        drop(3_500L)
        val dropAt = now
        tickAt(20_000L, dropAt)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR_AND_DASHCAM), posted)
        // The camera is still heard, which would sound the dashcam alarm.
        BatteryStateBus.markSeen("cam", 0L, dropAt + 29_000L)
        now = dropAt + 30_000L
        coordinator.evaluateWalkAway(now)
        assertEquals(0, dashcamPosts)
    }

    @Test
    fun theDashcamAlarmSoundingFirstKeepsThisOneSilent() {
        switchOffs(3)
        dashcamAlarmDue()
        walkAway()
        val dropAt = now
        BatteryStateBus.markSeen("cam", 0L, dropAt + 14_000L)
        now = dropAt + 15_000L
        coordinator.evaluateWalkAway(now)
        assertEquals(1, dashcamPosts)
        tickAt(20_000L, dropAt)
        // And still after the camera goes quiet and its alert auto-dismisses.
        now = dropAt + 60_000L
        coordinator.evaluateWalkAway(now)
        tickAt(62_000L, dropAt)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
    }

    private companion object {
        const val RADAR_A = "AA:AA:AA:AA:AA:01"
        const val RADAR_B = "AA:AA:AA:AA:AA:02"
    }
}
