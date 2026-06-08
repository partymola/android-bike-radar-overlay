// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * Invokes the real [RadarLinkCoordinator] - the safety-critical walk-away /
 * radar-drop state machine - with a controllable clock and spy effects, and
 * asserts on the resulting [RadarLinkState] and the effects fired. This
 * replaces the old hand-copied "mirror" in [RadarLinkStateTest], which
 * re-implemented the transition arithmetic in the test and so could never catch
 * a regression in the production code.
 *
 * Pins the things that have no pure decider behind them: the connect/disconnect
 * arithmetic (off-instant stamped on the FIRST disconnect, session-time
 * integration, the any->IDLE cluster clear), the arm/disarm gating, the
 * exactly-once IDLE effects, and the `setReconnectBanner`-before-`isPaused`
 * ordering in the radar-drop evaluation.
 */
@RunWith(RobolectricTestRunner::class)
class RadarLinkCoordinatorTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    private var now = 1_000_000L
    private var ebike: LiveDataSnapshot? = null
    private var ebikeAtMs = 0L
    private var dashcamSlug: String? = null

    private var postWalkAwayCount = 0
    private var cancelWalkAwayCount = 0
    private var alarmStartCount = 0
    private var alarmStopCount = 0
    private var snoozeCancelCount = 0
    private var dashcamBackoffClearCount = 0
    private val bannerStates = mutableListOf<Boolean>()
    private val clogLines = mutableListOf<String>()

    private lateinit var coordinator: RadarLinkCoordinator

    @Before
    fun setUp() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        BatteryStateBus.clearForTest()
        prefs = Prefs(app)
        coordinator = RadarLinkCoordinator(
            clock = { now },
            prefs = prefs,
            postWalkAwayNotification = { postWalkAwayCount++ },
            cancelWalkAwayNotification = { cancelWalkAwayCount++ },
            startWalkAwayAlarm = { alarmStartCount++ },
            stopWalkAwayAlarm = { alarmStopCount++ },
            alertBeeper = { null },
            clog = { clogLines += it },
            setReconnectBanner = { bannerStates += it },
            resolveDashcamSlug = { dashcamSlug },
            eBikeSnapshot = { ebike },
            eBikeSnapshotAtMs = { ebikeAtMs },
            cancelWalkAwaySnooze = { snoozeCancelCount++ },
            clearDashcamBackoff = { dashcamBackoffClearCount++ },
        )
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        BatteryStateBus.clearForTest()
    }

    private fun snap() = coordinator.snapshot()
    private fun clogged(token: String) = clogLines.count { it.contains(token) }

    private fun connectAt(t: Long) {
        now = t
        coordinator.markConnected()
    }

    private fun disconnectAt(t: Long) {
        now = t
        coordinator.markDisconnected()
    }

    // ── connect / disconnect arithmetic ──────────────────────────────────────

    @Test
    fun firstConnectSetsGattActiveAndStartAnchor() {
        connectAt(1_000L)
        val s = snap()
        assertTrue(s.radarGattActive)
        assertEquals(1_000L, s.radarConnectStartMs)
        assertNull(s.radarOffSinceMs)
        // No prior off-episode: no IDLE-transition effects, just the
        // unconditional dashcam-backoff clear + banner hide.
        assertEquals(1, dashcamBackoffClearCount)
        assertEquals(false, bannerStates.last())
        assertEquals(0, snoozeCancelCount)
        assertEquals(0, cancelWalkAwayCount)
        assertEquals(0, clogged("state=IDLE"))
    }

    @Test
    fun connectThenDisconnectIntegratesSessionTime() {
        connectAt(1_000L)
        disconnectAt(4_000L)
        val s = snap()
        assertFalse(s.radarGattActive)
        assertNull(s.radarConnectStartMs)
        assertEquals(3_000L, s.sessionRadarConnectedMs)
        assertEquals(4_000L, s.radarOffSinceMs)
    }

    @Test
    fun firstDisconnectArmsWhenNoEBike() {
        ebike = null
        connectAt(1_000L)
        disconnectAt(4_000L)
        assertTrue(snap().walkAwayArmed)
        assertEquals(1, clogged("state=ARMED"))
        assertEquals(0, clogged("state=BLANK"))
    }

    @Test
    fun firstDisconnectDoesNotArmWhenFreshlyUnlocked() {
        // eBike reports system_locked=false and the reading is fresh (age 4 s <
        // 30 s) at the disconnect instant: rider is on the bike, a radar BLE
        // blip must not arm.
        ebike = LiveDataSnapshot(systemLocked = false)
        connectAt(1_000_000L)
        ebikeAtMs = 1_004_000L // fresh stamp before the disconnect is read
        disconnectAt(1_004_000L)
        assertFalse(snap().walkAwayArmed)
        assertEquals(1, clogged("state=BLANK"))
        assertEquals(0, clogged("state=ARMED"))
    }

    @Test
    fun staleUnlockedSnapshotStillArms() {
        // system_locked=false but the snapshot is older than the 30 s trust
        // window at the disconnect instant: the eBike link itself dropped, so
        // "unlocked" can't be believed - arm.
        ebike = LiveDataSnapshot(systemLocked = false)
        connectAt(1_000_000L)
        ebikeAtMs = 1_004_000L - 40_000L // 40 s stale at the disconnect
        disconnectAt(1_004_000L)
        assertTrue(snap().walkAwayArmed)
        assertEquals(1, clogged("state=ARMED"))
    }

    @Test
    fun lockedEBikeArms() {
        ebike = LiveDataSnapshot(systemLocked = true) // locked -> always arms
        connectAt(1_000_000L)
        ebikeAtMs = 1_004_000L
        disconnectAt(1_004_000L)
        assertTrue(snap().walkAwayArmed)
        assertEquals(1, clogged("state=ARMED"))
    }

    @Test
    fun midEpisodeStutterKeepsOffInstantAndDoesNotReLog() {
        ebike = null
        connectAt(1_000L)
        disconnectAt(4_000L) // off-instant 4000, armed, one ARMED log
        disconnectAt(9_000L) // stutter: prev.radarOffSinceMs != null
        val s = snap()
        assertEquals("off-instant must not slide forward", 4_000L, s.radarOffSinceMs)
        assertEquals("no second transition log on a stutter", 1, clogged("state=ARMED"))
        assertEquals("no session time added with no connect anchor", 3_000L, s.sessionRadarConnectedMs)
    }

    @Test
    fun reconnectClearsWalkAwayClusterAndFiresIdleEffectsOnce() {
        ebike = null
        connectAt(1_000L)
        disconnectAt(4_000L) // armed
        coordinator.markWalkAwayDismissed() // dismissed
        assertTrue(snap().walkAwayArmed)
        assertTrue(snap().walkAwayDismissed)

        connectAt(9_000L)
        val s = snap()
        assertNull(s.radarOffSinceMs)
        assertFalse(s.walkAwayArmed)
        assertFalse(s.walkAwayDismissed)
        assertNull(s.lastWalkAwayFireMs)
        assertEquals(9_000L, s.radarConnectStartMs)
        assertTrue(s.radarGattActive)
        // IDLE effects fire exactly once for the observed off->on transition.
        assertEquals(1, snoozeCancelCount)
        assertEquals(1, cancelWalkAwayCount)
        assertEquals(1, clogged("state=IDLE"))
        assertEquals(1, clogged("prev_state=ARMED"))
        assertEquals(false, bannerStates.last())
    }

    @Test
    fun connectWithNoPriorOffDoesNotFireIdleEffects() {
        connectAt(1_000L)
        connectAt(2_000L) // still no off-episode between the two
        assertEquals(0, snoozeCancelCount)
        assertEquals(0, cancelWalkAwayCount)
        assertEquals(0, clogged("state=IDLE"))
        assertEquals(2_000L, snap().radarConnectStartMs)
        // dashcam-backoff clear + banner hide are unconditional, so they fire
        // on every connect.
        assertEquals(2, dashcamBackoffClearCount)
    }

    // ── tickWalkAwayState dashcam-stale BLANK disarm ──────────────────────────

    private val tickFreshMs = WalkAwayDecider.Config(enabled = false, thresholdMs = 0).dashcamFreshMs

    @Test
    fun tickDisarmsArmedOnceDashcamStale() {
        ebike = null
        dashcamSlug = "cam" // no BatteryStateBus entry -> lastAdvert 0 -> anchor = offAt
        connectAt(1_000L)
        disconnectAt(4_000L) // armed, off-instant 4000
        // anchor = 4000; disarm once now - 4000 > tickFreshMs
        coordinator.tickWalkAwayState(nowMs = 4_000L + tickFreshMs + 1, elapsedMs = 2_000L)
        assertFalse(snap().walkAwayArmed)
        assertEquals(1, clogged("dashcam-stale"))
    }

    @Test
    fun tickDoesNotDisarmAtTheStaleBoundary() {
        ebike = null
        dashcamSlug = "cam"
        connectAt(1_000L)
        disconnectAt(4_000L)
        // exactly at the window (strictly-greater-than), still armed
        coordinator.tickWalkAwayState(nowMs = 4_000L + tickFreshMs, elapsedMs = 2_000L)
        assertTrue(snap().walkAwayArmed)
        assertEquals(0, clogged("dashcam-stale"))
    }

    @Test
    fun tickIsNoOpWhenNotArmed() {
        connectAt(1_000L) // connected, never armed
        coordinator.tickWalkAwayState(nowMs = 10_000_000L, elapsedMs = 2_000L)
        assertFalse(snap().walkAwayArmed)
        assertEquals(0, clogged("dashcam-stale"))
    }

    // ── evaluateWalkAway FIRE / AUTO_DISMISS wiring ───────────────────────────

    /** Drive the coordinator to an ARMED, threshold-exceeded, fresh-dashcam
     *  state from which the decider returns FIRE. Returns the off-instant. */
    private fun armForFire(): Long {
        prefs.walkAwayAlarmEnabled = true
        prefs.dashcamWarnWhenOff = true
        dashcamSlug = "cam"
        ebike = null
        // 61 s connected clears the 60 s cold-start grace, then disconnect arms.
        connectAt(1_000_000L)
        disconnectAt(1_061_000L)
        return 1_061_000L
    }

    @Test
    fun evaluateWalkAwayFireWiring() {
        val off = armForFire()
        val fireAt = off + 31_000L // off 31 s > 30 s threshold
        BatteryStateBus.update(BatteryEntry("cam", "Cam", 80, readAtMs = fireAt - 1_000L))
        coordinator.evaluateWalkAway(fireAt)
        assertEquals(1, postWalkAwayCount)
        assertEquals(1, alarmStartCount)
        assertEquals(fireAt, snap().lastWalkAwayFireMs)
    }

    @Test
    fun evaluateWalkAwayAutoDismissWiring() {
        val off = armForFire()
        val fireAt = off + 31_000L
        BatteryStateBus.update(BatteryEntry("cam", "Cam", 80, readAtMs = fireAt - 1_000L))
        coordinator.evaluateWalkAway(fireAt) // FIRE, lastFire set
        assertEquals(fireAt, snap().lastWalkAwayFireMs)

        // Dashcam now goes stale (>20 s since last advert) -> AUTO_DISMISS.
        val dismissAt = fireAt + 25_000L
        coordinator.evaluateWalkAway(dismissAt)
        assertEquals(1, cancelWalkAwayCount)
        assertEquals(1, alarmStopCount)
        assertNull(snap().lastWalkAwayFireMs)
    }

    @Test
    fun evaluateWalkAwayDoesNothingBelowThreshold() {
        val off = armForFire()
        BatteryStateBus.update(BatteryEntry("cam", "Cam", 80, readAtMs = off))
        coordinator.evaluateWalkAway(off + 1_000L) // 1 s off, well under 30 s
        assertEquals(0, postWalkAwayCount)
        assertEquals(0, alarmStartCount)
        assertNull(snap().lastWalkAwayFireMs)
    }

    // ── evaluateRadarDrop banner ordering + cue ──────────────────────────────

    @Test
    fun pauseHidesBannerBeforeEarlyReturn() {
        // Banner toggle MUST run before the isPaused early-return, so a pause
        // hides an already-shown banner instead of leaving it stuck.
        prefs.pausedUntilEpochMs = Long.MAX_VALUE // paused
        connectAt(1_000L)
        disconnectAt(4_000L) // radarEverLive (session 3000 > 0), off 4000
        bannerStates.clear()
        coordinator.evaluateRadarDrop(4_000L + 11_000L) // down 11 s > 10 s visual
        assertEquals(listOf(false), bannerStates) // hidden, even though paused
        assertEquals(0, clogged("radar_drop_cue")) // audio path skipped when paused
    }

    @Test
    fun bannerShownWhenDownPastVisualThresholdAndNotPaused() {
        prefs.pausedUntilEpochMs = 0L
        connectAt(1_000L)
        disconnectAt(4_000L)
        bannerStates.clear()
        coordinator.evaluateRadarDrop(4_000L + 11_000L)
        assertEquals(listOf(true), bannerStates)
    }

    @Test
    fun radarDropCueFiresPastThresholdWhenRidingConfirmedThenLatches() {
        prefs.pausedUntilEpochMs = 0L
        ebike = LiveDataSnapshot(systemLocked = false)
        connectAt(1_000L)
        disconnectAt(4_000L) // radarEverLive, off 4000
        val fireAt = 4_000L + RadarLinkCoordinator.RADAR_DROP_THRESHOLD_MS + 1_000L
        ebikeAtMs = fireAt - 1_000L // fresh
        coordinator.evaluateRadarDrop(fireAt)
        assertEquals(1, clogged("radar_drop_cue"))
        // Within the cadence window the cue must not re-fire (latch).
        ebikeAtMs = fireAt // still fresh
        coordinator.evaluateRadarDrop(fireAt + 1_000L)
        assertEquals(1, clogged("radar_drop_cue"))
    }

    @Test
    fun radarDropSuppressedWhenLockedAndLogsOnce() {
        prefs.pausedUntilEpochMs = 0L
        ebike = LiveDataSnapshot(systemLocked = true) // locked -> riding not confirmed
        connectAt(1_000L)
        disconnectAt(4_000L)
        val t = 4_000L + RadarLinkCoordinator.RADAR_DROP_THRESHOLD_MS + 1_000L
        ebikeAtMs = t - 1_000L
        coordinator.evaluateRadarDrop(t)
        assertEquals(0, clogged("radar_drop_cue"))
        assertEquals(1, clogged("radar_drop_suppressed"))
        // One suppression line per down-episode, not per tick.
        coordinator.evaluateRadarDrop(t + 2_000L)
        assertEquals(1, clogged("radar_drop_suppressed"))
    }

    @Test
    fun noRadarDropCueForRadarOnlyRider() {
        prefs.pausedUntilEpochMs = 0L
        ebike = null // no eBike: no cue, nothing to suppress-log
        connectAt(1_000L)
        disconnectAt(4_000L)
        coordinator.evaluateRadarDrop(4_000L + RadarLinkCoordinator.RADAR_DROP_THRESHOLD_MS + 1_000L)
        assertEquals(0, clogged("radar_drop_cue"))
        assertEquals(0, clogged("radar_drop_suppressed"))
    }

    @Test
    fun suppressLatchResetsOnRadarReturn() {
        prefs.pausedUntilEpochMs = 0L
        ebike = LiveDataSnapshot(systemLocked = true)
        connectAt(1_000L)
        disconnectAt(4_000L)
        val t = 4_000L + RadarLinkCoordinator.RADAR_DROP_THRESHOLD_MS + 1_000L
        ebikeAtMs = t - 1_000L
        coordinator.evaluateRadarDrop(t)
        assertEquals(1, clogged("radar_drop_suppressed"))

        // Radar back, then an eval with the radar up resets the latch.
        connectAt(t + 1_000L)
        coordinator.evaluateRadarDrop(t + 2_000L)
        // New down-episode, suppressed again -> logs a second time.
        disconnectAt(t + 3_000L)
        val t2 = t + 3_000L + RadarLinkCoordinator.RADAR_DROP_THRESHOLD_MS + 1_000L
        ebikeAtMs = t2 - 1_000L
        coordinator.evaluateRadarDrop(t2)
        assertEquals(2, clogged("radar_drop_suppressed"))
    }

    @Test
    fun radarReconnectCueFiresAfterDropCueOnRadarReturn() {
        // After a real drop cue, the tick that sees the radar back up
        // acknowledges with a reconnect cue (closes the "back vs still dead"
        // ambiguity). This is the coordinator wiring the decider tests can't see.
        prefs.pausedUntilEpochMs = 0L
        ebike = LiveDataSnapshot(systemLocked = false)
        connectAt(1_000L)
        disconnectAt(4_000L)
        val dropAt = 4_000L + RadarLinkCoordinator.RADAR_DROP_THRESHOLD_MS + 1_000L
        ebikeAtMs = dropAt - 1_000L
        coordinator.evaluateRadarDrop(dropAt)
        assertEquals(1, clogged("radar_drop_cue"))

        connectAt(dropAt + 1_000L) // radar back: off-instant cleared
        coordinator.evaluateRadarDrop(dropAt + 2_000L) // downForMs == null
        assertEquals(1, clogged("radar_reconnect_cue"))
    }

    @Test
    fun noReconnectCueWhenNoDropCueWasRaised() {
        // A brief drop that never reached the cue threshold must not produce a
        // reconnect acknowledgement on return.
        prefs.pausedUntilEpochMs = 0L
        ebike = LiveDataSnapshot(systemLocked = false)
        connectAt(1_000L)
        disconnectAt(4_000L)
        coordinator.evaluateRadarDrop(4_000L + 11_000L) // 11 s: banner only, no cue
        assertEquals(0, clogged("radar_drop_cue"))
        connectAt(20_000L)
        coordinator.evaluateRadarDrop(21_000L)
        assertEquals(0, clogged("radar_reconnect_cue"))
    }

    // ── snooze re-arm helper ─────────────────────────────────────────────────

    @Test
    fun clearWalkAwayDismissalForReArmResetsBothGates() {
        ebike = null
        connectAt(1_000L)
        disconnectAt(4_000L)
        coordinator.markWalkAwayDismissed()
        assertTrue(snap().walkAwayDismissed)
        coordinator.clearWalkAwayDismissalForReArm()
        assertFalse(snap().walkAwayDismissed)
        assertNull(snap().lastWalkAwayFireMs)
    }
}
