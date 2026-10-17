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
    private val clogs = mutableListOf<String>()

    private var ridingActivityMs: Long? = null
    private var ridingSinceMs: Long? = null
    private var ebike: LiveDataSnapshot? = null

    /** When [ebike] arrived; null means just now. */
    private var ebikeAtMs: Long? = null
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
            clog = { clogs += it },
            journal = { journal += it },
            setReconnectBanner = {},
            resolveDashcamSlug = { dashcamSlug },
            eBikeSnapshot = { ebike },
            eBikeSnapshotAtMs = { ebikeAtMs ?: now },
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
        assertTrue("the capture log says why", clogs.contains("# walkaway radar_left_on=calibrating switch_offs=2"))
        tickAt(20_000L, dropAt)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
        // The third clean switch-off arms the next walk-away.
        now += 60_000L
        switchOffs(1)
        walkAway()
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
        assertTrue(clogs.contains("# walkaway radar_left_on=qualified switch_offs=3"))
    }

    @Test
    fun aLinkBluetoothEndedLeavesCalibrationAlone() {
        // The drop reported after the adapter-off broadcast, the one order the
        // guard covers.
        switchOffs(2)
        coordinator.markAdapterOff()
        stream()
        now += 600_000L
        drop(300L, status = 257)
        assertEquals("not counted", 2, prefs.radarLeftOnSwitchOffs(RADAR_A))
        coordinator.markAdapterOn()
        now += 60_000L
        coordinator.markAdapterOff()
        stream()
        now += 600_000L
        drop(1_500L, status = 257)
        assertEquals("nor restarted", 2, prefs.radarLeftOnSwitchOffs(RADAR_A))
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
        switchOffs(3)
        stream()
        now += 600_000L
        drop(3_500L, status = 22)
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
        assertEquals("nor resets it", 3, prefs.radarLeftOnSwitchOffs(RADAR_A))
    }

    @Test
    fun anUnclearEndingStartsCalibrationAgain() {
        switchOffs(3)
        stream()
        now += 600_000L
        drop(1_500L, status = 257)
        assertEquals(0, prefs.radarLeftOnSwitchOffs(RADAR_A))
        assertTrue(journal.contains("walk-away alarm calibration restarted: the radar link ended unclearly"))
        walkAway()
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
    }

    @Test
    fun aLinkThatStreamedOnlyBrieflyDoesNotQualify() {
        // A radar that connects as the ride starts and drops again.
        switchOffs(3)
        stream()
        now += 14_000L
        drop(3_500L)
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
        now += 60_000L
        stream()
        now += 15_000L
        drop(3_500L)
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
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
        // The run stays open for two minutes after the last confirmation, and
        // a rider who locks up and walks off is well inside that.
        switchOffs(3)
        ebike = LiveDataSnapshot(systemLocked = true)
        ridingSinceMs = 1_000L
        walkAway()
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
    }

    @Test
    fun aStaleUnlockedEBikeRidingRunDoesNotSilenceItEither() {
        // The same run with no lock reading to skip the window on.
        switchOffs(3)
        stream()
        now += 600_000L
        ebike = LiveDataSnapshot(systemLocked = false)
        ebikeAtMs = now - 31_000L
        ridingSinceMs = 1_000L
        drop(3_500L)
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
    }

    @Test
    fun aLockReadBeforeTheRidingDoesNotSkipIt() {
        // Locked at an earlier stop, the unlock never heard, then ridden on.
        switchOffs(3)
        stream()
        now += 600_000L
        ebike = LiveDataSnapshot(systemLocked = true)
        ebikeAtMs = now - 300_000L
        ridingActivityMs = now - 10_000L
        drop(3_500L)
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
    }

    @Test
    fun anEdgeOfRangeReconnectStaysSilent() {
        // Accepted: the reconnect ends the first check, and the second link is
        // too short to qualify.
        switchOffs(3)
        walkAway()
        now += 10_000L
        stream()
        now += 10_000L
        drop(3_500L)
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
    }

    @Test
    fun aLockedBikeSkipsTheRidingWindow() {
        // Locked and walked straight off: the last moving frame is inside the
        // 30 s window, and a locked bike is not being ridden.
        switchOffs(3)
        ebike = LiveDataSnapshot(systemLocked = true)
        stream()
        now += 600_000L
        ridingActivityMs = now - 10_000L
        drop(3_500L)
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
    }

    @Test
    fun aStaleUnlockedReadingStillArmsIt() {
        // The eBike link dropped before the radar did: its "unlocked" is old.
        switchOffs(3)
        stream()
        now += 600_000L
        ebike = LiveDataSnapshot(systemLocked = false)
        ebikeAtMs = now - 31_000L
        drop(3_500L)
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
    fun bluetoothOffAndBackOnInTheEpisodeStaysSilent() {
        switchOffs(3)
        walkAway()
        coordinator.markAdapterOff()
        coordinator.markAdapterOn()
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
    }

    @Test
    fun turningTheSwitchOffDuringTheCheckReleasesItsWakeLock() {
        switchOffs(3)
        walkAway()
        assertTrue(wakeLockHeld)
        prefs.radarLeftOnAlarmEnabled = false
        tickAt(2_000L, now)
        assertFalse(wakeLockHeld)
        tickAt(20_000L, now)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
    }

    @Test
    fun reconnectAttemptsAfterTheDropDoNotReArmIt() {
        // Each failed attempt of the reconnect loop ends with a disconnect edge.
        switchOffs(3)
        walkAway()
        val dropAt = now
        tickAt(20_000L, dropAt)
        coordinator.markDisconnected(null)
        coordinator.markDisconnected(null)
        assertEquals(1, wakeLockAcquires)
        assertEquals(1, journal.count { it == "walk-away alarm armed: the radar may have been left on" })
        assertFalse(wakeLockHeld)
    }

    @Test
    fun theRadarLeftOnWakeLockOutlastsTheCheck() {
        assertEquals(35_000L, RadarLinkCoordinator.RADAR_LEFT_ON_WAKELOCK_CAP_MS)
    }

    @Test
    fun aDropWhileBluetoothIsOffStartsNoCheck() {
        switchOffs(3)
        coordinator.markAdapterOff()
        walkAway()
        assertEquals(0, wakeLockAcquires)
        assertTrue("the capture log says why", clogs.contains("# walkaway radar_left_on=bluetooth-off switch_offs=3"))
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
        BatteryStateBus.markSeen("cam", 0L, now - 30_000L)
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
        BatteryStateBus.markSeen("cam", 0L, now - 30_001L)
        drop(3_500L)
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
    }

    @Test
    fun anAlarmInOneOffEpisodeDoesNotSilenceTheNext() {
        // The fired latch and the owner are the episode's, so the radar's next
        // stream clears them; neither is rewritten by the next drop.
        switchOffs(3)
        walkAway()
        tickAt(20_000L, now)
        now += 60_000L
        walkAway()
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR, WalkAwayAlarmKind.RADAR), posted)
    }

    @Test
    fun aDashcamAlarmInOneOffEpisodeDoesNotSilenceTheNext() {
        switchOffs(3)
        dashcamAlarmDue()
        // The radar switched off, the camera still heard.
        stream()
        now += 600_000L
        drop(300L, status = 19)
        val firstDrop = now
        BatteryStateBus.markSeen("cam", 0L, firstDrop + 14_000L)
        now = firstDrop + 15_000L
        coordinator.evaluateWalkAway(now)
        assertEquals(1, dashcamPosts)
        now += 60_000L
        walkAway()
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
    }

    @Test
    fun theDashcamAlarmDoesNotNeedTheOverlayWarning() {
        dashcamAlarmDue()
        assertFalse(prefs.dashcamWarnWhenOff)
        stream()
        now += 600_000L
        drop(300L, status = 19)
        val dropAt = now
        BatteryStateBus.markSeen("cam", 0L, dropAt + 14_000L)
        now = dropAt + 15_000L
        coordinator.evaluateWalkAway(now)
        assertEquals(1, dashcamPosts)
        assertEquals(1, starts)
    }

    @Test
    fun aDismissHoldsUntilTheRiderRidesAgain() {
        // A radar left on at the edge of range reconnects and drops again;
        // the rider has answered once.
        switchOffs(3)
        walkAway()
        val dropAt = now
        tickAt(20_000L, dropAt)
        coordinator.markWalkAwayDismissed(snoozed = false)
        tickAt(200_000L, dropAt)
        now += 60_000L
        walkAway()
        tickAt(20_000L, now)
        assertEquals("the dismiss holds across reconnects", 1, posted.size)
        // Riding again ends it: the next ride's walk-away sounds.
        stream()
        now += 600_000L
        ridingActivityMs = now - 120_000L
        drop(3_500L)
        tickAt(20_000L, now)
        assertEquals(2, posted.size)
    }

    @Test
    fun aDismissOfTheAlarmNamingTheCameraHoldsToo() {
        switchOffs(3)
        dashcamSlug = "cam"
        BatteryStateBus.update(BatteryEntry("cam", "Cam", 80))
        stream()
        now += 600_000L
        BatteryStateBus.markSeen("cam", 0L, now - 1_000L)
        drop(3_500L)
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR_AND_DASHCAM), posted)
        coordinator.markWalkAwayDismissed(snoozed = false)
        now += 60_000L
        walkAway()
        tickAt(20_000L, now)
        assertEquals("the dismiss holds across reconnects", 1, posted.size)
    }

    @Test
    fun aDismissOfTheDashcamAlarmDoesNotHoldThisOne() {
        switchOffs(3)
        dashcamAlarmDue()
        stream()
        now += 600_000L
        drop(300L, status = 19)
        val dropAt = now
        BatteryStateBus.markSeen("cam", 0L, dropAt + 14_000L)
        now = dropAt + 15_000L
        coordinator.evaluateWalkAway(now)
        assertEquals(1, dashcamPosts)
        coordinator.markWalkAwayDismissed(snoozed = false)
        now += 60_000L
        walkAway()
        tickAt(20_000L, now)
        assertEquals(1, posted.size)
    }

    @Test
    fun bluetoothOffHoldsTheDashcamAlarmToo() {
        // The radar check is over, and the dashcam alarm stays held with it.
        switchOffs(3)
        dashcamAlarmDue()
        stream()
        now += 600_000L
        BatteryStateBus.markSeen("cam", 0L, now - 1_000L)
        drop(3_500L)
        val dropAt = now
        coordinator.markAdapterOff()
        BatteryStateBus.markSeen("cam", 0L, dropAt + 14_000L)
        now = dropAt + 15_000L
        coordinator.evaluateWalkAway(now)
        tickAt(20_000L, dropAt)
        assertEquals(0, dashcamPosts)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
    }

    @Test
    fun aSnoozeIsNotADismissal() {
        switchOffs(3)
        walkAway()
        tickAt(20_000L, now)
        coordinator.markWalkAwayDismissed(snoozed = true)
        now += 60_000L
        walkAway()
        tickAt(20_000L, now)
        assertEquals(2, posted.size)
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
    fun theSwitchTurnedOnAfterTheDropDoesNotSoundIt() {
        switchOffs(3)
        prefs.radarLeftOnAlarmEnabled = false
        walkAway()
        val dropAt = now
        prefs.radarLeftOnAlarmEnabled = true
        tickAt(20_000L, dropAt)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
        assertEquals(0, wakeLockAcquires)
        assertTrue(clogs.contains("# walkaway radar_left_on=switched-off switch_offs=3"))
    }

    @Test
    fun theDashcamSwitchOffDoesNotSilenceIt() {
        prefs.walkAwayAlarmEnabled = false
        switchOffs(3)
        walkAway()
        tickAt(20_000L, now)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR), posted)
    }

    @Test
    fun aReconnectAsItSoundsLeavesItStopped() {
        switchOffs(3)
        walkAway()
        val cancelsBefore = cancels
        val stopsBefore = stops
        onPost = { stream() }
        tickAt(20_000L, now)
        assertEquals(1, posted.size)
        // One from the reconnect, which ran before the start, one undoing it.
        assertEquals(cancelsBefore + 2, cancels)
        assertEquals(stopsBefore + 2, stops)
        assertFalse(journal.contains("walk-away alarm sounded: radar left on"))
    }

    @Test
    fun aDismissAsItSoundsLeavesItStopped() {
        switchOffs(3)
        walkAway()
        val cancelsBefore = cancels
        val stopsBefore = stops
        onPost = { coordinator.markWalkAwayDismissed(snoozed = false) }
        tickAt(20_000L, now)
        assertEquals(cancelsBefore + 1, cancels)
        assertEquals(stopsBefore + 1, stops)
    }

    /** The dashcam alarm armed and due: camera heard, threshold passed. The
     *  overlay's camera-off warning stays at its default, off. */
    private fun dashcamAlarmDue() {
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
        // It owns the episode, so the switch turned off afterwards does not
        // hand the camera to the other alarm.
        prefs.radarLeftOnAlarmEnabled = false
        BatteryStateBus.markSeen("cam", 0L, dropAt + 59_000L)
        now = dropAt + 60_000L
        coordinator.evaluateWalkAway(now)
        assertEquals(0, dashcamPosts)
    }

    @Test
    fun aPendingRadarCheckHoldsTheDashcamAlarm() {
        // At a 15 s threshold the dashcam alarm would sound first, then
        // auto-dismiss as the camera goes out of range too.
        switchOffs(3)
        dashcamAlarmDue()
        stream()
        now += 600_000L
        BatteryStateBus.markSeen("cam", 0L, now - 1_000L)
        drop(3_500L)
        val dropAt = now
        BatteryStateBus.markSeen("cam", 0L, dropAt + 14_000L)
        now = dropAt + 15_000L
        coordinator.evaluateWalkAway(now)
        assertEquals(0, dashcamPosts)
        tickAt(20_000L, dropAt)
        assertEquals(listOf(WalkAwayAlarmKind.RADAR_AND_DASHCAM), posted)
    }

    @Test
    fun theDashcamAlarmSoundingFirstKeepsThisOneSilent() {
        // Only reachable with the radar alarm switched off during its check,
        // and on again once the dashcam's has sounded.
        switchOffs(3)
        dashcamAlarmDue()
        walkAway()
        val dropAt = now
        prefs.radarLeftOnAlarmEnabled = false
        BatteryStateBus.markSeen("cam", 0L, dropAt + 14_000L)
        now = dropAt + 15_000L
        coordinator.evaluateWalkAway(now)
        assertEquals(1, dashcamPosts)
        prefs.radarLeftOnAlarmEnabled = true
        val cancelsBefore = cancels
        val stopsBefore = stops
        // The camera goes quiet: the dashcam alarm still owns the episode, so
        // it auto-dismisses rather than being held for the radar's.
        now = dropAt + 60_000L
        coordinator.evaluateWalkAway(now)
        assertEquals(cancelsBefore + 1, cancels)
        assertEquals(stopsBefore + 1, stops)
        tickAt(62_000L, dropAt)
        assertEquals(emptyList<WalkAwayAlarmKind>(), posted)
    }

    private companion object {
        const val RADAR_A = "AA:AA:AA:AA:AA:01"
        const val RADAR_B = "AA:AA:AA:AA:AA:02"
    }
}
