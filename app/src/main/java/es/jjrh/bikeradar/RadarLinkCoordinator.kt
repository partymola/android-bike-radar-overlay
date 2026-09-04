// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Owner of the rear-radar link state and the walk-away / radar-drop safety
 * state machine that watches it. Holds the single [RadarLinkState] StateFlow so
 * multi-field transitions are atomic against readers, and drives the two
 * rider-facing safety outputs gated on it: the dismount (walk-away) alarm and
 * the dropped-radar audio cue + reconnecting banner.
 *
 * Extracted off [BikeRadarService] so the transitions are unit-testable: the
 * service builds one of these in `onCreate`, hands it the side-effect
 * collaborators as constructor lambdas (clock, notifications, alarm, beeper,
 * capture-log writer, reconnect-banner toggle, dashcam-slug resolver, the eBike
 * snapshot reads), and routes the GATT connect/disconnect callbacks through it
 * via [RadarLinkStateGateway]. The pure decisions live in [WalkAwayDecider],
 * [WalkAwayArmingGate], [RadarDropDecider] and [RadarLinkVisualDecider]; this
 * class is the stateful orchestration that feeds them and fires the effects.
 *
 * The clock is injected as [clock] (monotonic `SystemClock.elapsedRealtime()` in
 * production) so tests can pin the off-instant, session-time integration and
 * re-fire cadences deterministically.
 */
internal class RadarLinkCoordinator(
    private val clock: () -> Long,
    private val prefs: Prefs,
    private val postWalkAwayNotification: () -> Unit,
    private val cancelWalkAwayNotification: () -> Unit,
    private val startWalkAwayAlarm: () -> Unit,
    private val stopWalkAwayAlarm: () -> Unit,
    private val alertBeeper: () -> AlertBeeper?,
    private val clog: (String) -> Unit,
    private val setReconnectBanner: (RadarLinkVisualDecider.LinkVisual) -> Unit,
    private val resolveDashcamSlug: () -> String?,
    private val eBikeSnapshot: () -> LiveDataSnapshot?,
    private val eBikeSnapshotAtMs: () -> Long,
    // Whether the eBike's own speed shows a recent sustained ride (RidingSpeedGate).
    // The radar-drop cue's eBike confirmation: an unlocked bike is merely awake,
    // and a garage power-on must not read as a mid-ride radar failure.
    private val eBikeRidingFresh: (Long) -> Boolean,
    private val hasEBikeSignal: () -> Boolean,
    private val everSawTrack: () -> Boolean,
    private val postForgotToLock: () -> Unit,
    private val cancelForgotToLock: () -> Unit,
    private val cancelWalkAwaySnooze: () -> Unit,
    private val clearDashcamBackoff: () -> Unit,
    // Monotonic instant of the last decoded frame that showed real riding
    // activity (rider speed above walking pace), or null if none this session.
    // Sampled at the disconnect instant to confirm a radar-only rider was
    // mid-ride (radar-drop cue) and to decide whether to hold the ride wakelock.
    private val lastRidingActivityMs: () -> Long?,
    // Monotonic instant of the last frame from a range-only radar that showed a
    // vehicle, or null if none this session. The track-presence fallback's only
    // input: it stands in for rider speed on a stream that has none, and only
    // for a rider with no eBike (see [RadarDropDecider.trackActivityFreshAtDrop]).
    private val lastTrackActivityMs: () -> Long?,
    // Drop the track sighting when a reconnect starts a NEW RIDE. Depth rather
    // than the live guard: a new ride needs a gap of at least
    // radarLongOfflineThresholdMinutes, which floors at 5 min, so the carried
    // sighting is already older than both freshness windows (30 s and 120 s)
    // at any later drop and would be rejected anyway. It earns its line against
    // a future source that could stamp mid-episode, such as a replayed capture.
    private val clearTrackActivity: () -> Unit,
    // Wake the walk-away tick loop out of its idle delay so it flips to the
    // fast cadence the instant the radar drops (no up-to-30 s lag on the first
    // dead-radar evaluation).
    private val wakeTick: () -> Unit,
    // Bounded ride wakelock for a live-ride off-episode (see [RideWakeLock]).
    private val acquireRideWakeLock: () -> Unit,
    private val releaseRideWakeLock: () -> Unit,
) : RadarLinkStateGateway {

    private val _radarLinkState = MutableStateFlow(RadarLinkState())
    val radarLinkState: StateFlow<RadarLinkState> = _radarLinkState

    // Re-fire latch for the radar-drop cue + a one-shot "suppressed" diagnostic
    // flag, both scoped to the current off-episode. Reset lazily in
    // [evaluateRadarDrop] on radar return.
    //
    // TWO WRITERS since the new-ride reset landed: the tick loop
    // ([evaluateRadarDrop]) and the BLE callback thread ([markConnected]). A
    // tick that computed its decision while the radar was still down can write
    // its latch back after markConnected nulled it, which resurrects the stale
    // acknowledgement pulse the reset exists to stop. The window is one method
    // body and the cost is a single spurious beep, so it is tolerated rather
    // than locked; the fix is to move the reset onto the tick beside the
    // explicitParked one, which is where the rider-driven ride-end signal will
    // put it.
    @Volatile private var radarDropLastCueMs: Long? = null

    // Cues fired this off-episode; caps the latch-only confirmation path
    // (RadarDropDecider.MAX_LATCH_ONLY_CUES) so a quick park cannot repeat
    // the cue forever. Reset with the lastCue latch on radar return.
    @Volatile private var radarDropCueCount = 0

    @Volatile private var radarDropSuppressLogged = false

    // Radar-activity riding confirmation, LATCHED at the disconnect instant for
    // the current off-episode: was the rider moving above walking pace within
    // the freshness window when the radar dropped, or - on a range-only radar
    // that cannot report that, for a rider with no eBike - was a vehicle
    // reported within it? The radar stops streaming after the drop, so unlike
    // the live eBike snapshot neither can be re-read per tick; both are sampled
    // once in [markDisconnected] and reset in [markConnected] on radar return.
    @Volatile private var radarActivityFreshAtDrop = false

    // The track-presence half of the same latch, kept SEPARATE so the rider's
    // Experimental toggle can be applied per tick rather than frozen at the
    // drop, and so the drop cue knows which signal it is running on. Sampled
    // and reset on the same edges as [radarActivityFreshAtDrop].
    @Volatile private var trackFreshAtDrop = false

    // Fired-once latch for the forgot-to-lock wrist haptic, scoped to the current
    // off-episode; reset in markConnected on radar return.
    @Volatile private var forgotToLockFired = false

    override fun snapshot(): RadarLinkState = _radarLinkState.value

    /** Rider dismissed the walk-away alarm for this off-episode. */
    fun markWalkAwayDismissed() {
        _radarLinkState.update { it.copy(walkAwayDismissed = true) }
    }

    /** Snooze window elapsed: clear both gates so the decider re-evaluates the
     *  episode cleanly rather than re-firing immediately. */
    fun clearWalkAwayDismissalForReArm() {
        _radarLinkState.update { it.copy(walkAwayDismissed = false, lastWalkAwayFireMs = null) }
    }

    /** Off-instant is stamped at the actual disconnect callback so it
     *  isn't tied to tick cadence (the idle tick is 30 s; that would
     *  drift the walk-away threshold by up to 30 s). Clean-reconnect
     *  cleanup likewise fires at the connection-success site, not
     *  lazily on the next tick.
     *
     *  Side effects (notification cancel, snooze-job cancel, clog) sit
     *  OUTSIDE the [update] lambda - the lambda may run multiple times
     *  on a CAS retry, but these effects must fire exactly once per
     *  observed transition. The snapshot read of the prior state is the
     *  arbiter for whether to fire the effects. */
    override fun markConnected() {
        val nowMs = clock()
        val prev = _radarLinkState.value
        _radarLinkState.update { current ->
            if (current.radarOffSinceMs != null) {
                // Any → IDLE: radar is back, leave-behind tracking off.
                // Re-arming requires the next radar disconnect.
                current.copy(
                    radarOffSinceMs = null,
                    walkAwayArmed = false,
                    walkAwayDismissed = false,
                    lastWalkAwayFireMs = null,
                    radarConnectStartMs = nowMs,
                    radarGattActive = true,
                )
            } else {
                current.copy(
                    radarConnectStartMs = nowMs,
                    radarGattActive = true,
                )
            }
        }
        // New radar presence episode: clear dashcam-probe backoff so the camera
        // is re-probed promptly this ride (the storm guard resets per ride).
        clearDashcamBackoff()
        if (prev.radarOffSinceMs != null) {
            val prevState = if (prev.walkAwayArmed) "ARMED" else "BLANK"
            cancelWalkAwaySnooze()
            cancelWalkAwayNotification()
            // Radar back = rider returned to the bike: clear the forgot-to-lock
            // reminder and re-arm it for the next off-episode.
            forgotToLockFired = false
            cancelForgotToLock()
            // Off-episode over: reset the radar-activity latch so the next drop
            // re-samples freshness, and free the ride wakelock.
            radarActivityFreshAtDrop = false
            trackFreshAtDrop = false
            // A reconnect past the app's own parked boundary is a NEW ride, so
            // close out the previous ride's cue bookkeeping and its traffic
            // sighting. The reconnect acknowledgement pulse fires only when a
            // drop cue was raised, and nothing else clears that latch for a
            // rider with no eBike, so tomorrow's first connect would otherwise
            // open with a lone beep acknowledging a drop they heard yesterday.
            // The eBike cohort already gets this from an explicit lock (see
            // evaluateRadarDrop).
            //
            // Scoped to the NEW-RIDE case on purpose. Clearing the sighting on
            // every reconnect looks tidier and costs a cue: a radar that drops,
            // returns after the corpus-median 8 s and drops again over an empty
            // stretch is a genuinely mid-ride drop whose only evidence is the
            // traffic seen before the first drop. Losing that is a MISSED cue,
            // the side of the asymmetry this whole gate treats as the worse
            // error, and it would also make the code stricter than the corpus
            // figure documenting it, which was replayed without such a wipe.
            val newRide = RideSummaryNotificationDecider.shouldStartNewRide(
                nowMs - prev.radarOffSinceMs,
                prefs.radarLongOfflineThresholdMinutes * 60_000L,
            )
            if (newRide) {
                radarDropLastCueMs = null
                radarDropCueCount = 0
                clearTrackActivity()
            }
            releaseRideWakeLock()
            clog(
                "# walkaway state=IDLE transition_reason=radar-connected " +
                    "prev_state=$prevState new_ride=$newRide",
            )
        }
        // Radar is back: hide the reconnecting banner now rather than waiting
        // for the next (up to 30s idle) tick of evaluateRadarDrop.
        setReconnectBanner(RadarLinkVisualDecider.LinkVisual.LIVE)
    }

    override fun markDisconnected() {
        val nowMs = clock()
        val prev = _radarLinkState.value
        val freshOffEpisode = prev.radarOffSinceMs == null
        // Computed once from prev and reused across any CAS retries below.
        // walkAwayArmed is monotonic within an off-episode (only cleared by
        // markConnected / tickWalkAwayState BLANK), so re-evaluating
        // it per retry wouldn't change the post-state semantically.
        val armOnDisconnect = freshOffEpisode &&
            WalkAwayArmingGate.shouldArm(
                eBikeSnapshot(),
                snapshotAgeMs = nowMs - eBikeSnapshotAtMs(),
                freshMs = WALKAWAY_EBIKE_FRESH_MS,
            )
        // Sample the radar-activity riding signal ONCE, at the drop instant: the
        // radar stops streaming now, so this can't be re-read per tick. Latched
        // for the whole off-episode; reset in markConnected. Only on the first
        // disconnect of an episode (a stutter must not re-sample against a
        // now-stale last-activity instant). Both latches are pinned inside this
        // guard by aBleStutterDoesNotReSampleTheSpeedLatch and its track-latch
        // twin; hoisting either line out silences a genuine mid-ride cue.
        val lastActivityMs = lastRidingActivityMs()
        if (freshOffEpisode) {
            radarActivityFreshAtDrop = RadarDropDecider.activityFreshAtDrop(
                nowMs,
                lastActivityMs,
                RADAR_DROP_ACTIVITY_FRESH_MS,
            )
            // Fallback for a range-only radar with no eBike, where the speed
            // latch above can never be true. Sampled here, in the same instant
            // and for the same reason - but WITHOUT the rider's toggle, which
            // [evaluateRadarDrop] applies per tick instead. Folding the toggle
            // in here would freeze it for the whole off-episode, so a rider
            // switching it off on hearing an unwanted cue would still get the
            // rest of the episode's repeats, which is not what the setting
            // promises.
            trackFreshAtDrop = !radarActivityFreshAtDrop &&
                RadarDropDecider.trackActivityFreshAtDrop(
                    nowMs,
                    lastTrackActivityMs(),
                    RADAR_DROP_TRACK_FRESH_MS,
                    hasEBikeSignal = hasEBikeSignal(),
                )
            // Names the latch this drop set, not the signal a cue fired on -
            // no cue decision has been taken yet at this instant.
            if (trackFreshAtDrop) clog("# radar_drop_latch source=track-presence")
        }
        _radarLinkState.update { current ->
            val addedMs = current.radarConnectStartMs?.let { nowMs - it } ?: 0L
            current.copy(
                radarGattActive = false,
                radarConnectStartMs = null,
                sessionRadarConnectedMs = current.sessionRadarConnectedMs + addedMs,
                // Off-instant is stamped on the FIRST disconnect; a stutter
                // mid-off-episode must not refresh it.
                radarOffSinceMs = current.radarOffSinceMs ?: nowMs,
                // Consult the eBike snapshot before arming. When the bike
                // reports system_locked=false the rider is on the bike
                // (mid-ride radar BLE blip); arming would misfire. Any other
                // case (locked, null systemLocked, null snapshot, eBike flag
                // off) falls through to the existing IDLE → ARMED path.
                walkAwayArmed = current.walkAwayArmed || armOnDisconnect,
            )
        }
        if (freshOffEpisode) {
            // Flip the walk-away tick loop to its fast cadence immediately, so the
            // first dead-radar evaluation (and the "10 s" banner) lands on time
            // instead of waiting out up to 30 s of the idle-tick delay.
            wakeTick()
            // Hold the CPU through a live-ride off-episode so the delay() timers
            // (dead-radar cue, walk-away alarm, ride summary) don't sleep past
            // their deadlines in deep Doze for a rider with no BLE wakeups. Gated
            // on recent riding activity (a separate, wider window than the cue's
            // own gate) so a radar that drops long after the rider parked - the
            // manifest's old "parked-phone idle" concern - never acquires it. The
            // lock is bounded (RIDE_WAKELOCK_CAP_MS) and released on reconnect /
            // BLANK / ride-summary.
            // Both confirmation signals, at the wakelock's own wider window.
            // Gating this on rider speed alone left the range-only cohort with
            // a confirmed live-ride off-episode and no Doze protection - their
            // speed latch is structurally null for the whole ride, so the gate
            // could never open for precisely the rider the sentence above
            // describes, and the cue would fire late or not at all with the
            // screen off while looking correct on any bench test.
            //
            // What this does NOT deliver, so nobody reads more into it: a rider
            // with no dashcam loses the lock 20 s into the off-episode, because
            // tickWalkAwayState disarms to BLANK at dashcamFreshMs when there is
            // no advert to advance the anchor, and BLANK releases. The first cue
            // is at 60 s. Their exposure is Doze only - BLANK leaves
            // radarOffSinceMs set, so the 2 s tick cadence continues and the cue
            // is on time whenever the CPU is awake. The release path is
            // unchanged from before this fallback existed.
            //
            // Deliberately NOT gated on the drop-cue toggle. This lock protects
            // every off-episode timer, walk-away and the ride summary included,
            // and a rider who switched the cue off still has those. Holding it
            // for them costs a bounded 300 s that reconnect or BLANK ends
            // sooner; not holding it would break two features to spare one.
            val liveRideAtDrop = RadarDropDecider.activityFreshAtDrop(
                nowMs,
                lastActivityMs,
                RIDE_WAKELOCK_ACTIVITY_FRESH_MS,
            ) ||
                RadarDropDecider.trackActivityFreshAtDrop(
                    nowMs,
                    lastTrackActivityMs(),
                    RIDE_WAKELOCK_ACTIVITY_FRESH_MS,
                    hasEBikeSignal = hasEBikeSignal(),
                )
            if (liveRideAtDrop) {
                acquireRideWakeLock()
            }
            // ebike_locked + ebike_age_ms make the arming decision tunable: a
            // BLANK is always a fresh unlocked reading; an ARMED is one of
            // locked / stale-unlocked / no-eBike, told apart by these two.
            val ebikeAgeMs = nowMs - eBikeSnapshotAtMs()
            if (armOnDisconnect) {
                clog(
                    "# walkaway state=ARMED transition_reason=radar-disconnected " +
                        "ebike_locked=${eBikeSnapshot()?.systemLocked} ebike_age_ms=$ebikeAgeMs",
                )
            } else {
                clog(
                    "# walkaway state=BLANK transition_reason=radar-disconnected-but-ebike-unlocked " +
                        "ebike_age_ms=$ebikeAgeMs",
                )
            }
        }
    }

    fun tickWalkAwayState(nowMs: Long, elapsedMs: Long) {
        // sessionRadarConnectedMs is integrated on connect→disconnect
        // transitions in [markDisconnected], not per-tick. The idle
        // tick is 30 s; a connection that ends within that window would
        // never have its duration counted under the old per-tick scheme.

        // ARMED → BLANK transition: while the radar is still off, watch
        // for the dashcam going stale. The "stale window" is anchored at
        // the LATER of (radarOffSinceMs, dashcamLastAdvertMs):
        //   - if the dashcam has adverted since radar-off, the window
        //     starts at the most recent advert (rider walked away from
        //     a continuously-fresh dashcam, then dashcam dropped out);
        //   - if the dashcam was already silent at radar-off, the window
        //     starts at the disconnect itself (rider stopped with the
        //     camera already off - never was a leave-behind risk).
        // Once the window exceeds dashcamFreshMs we declare BLANK; the
        // alarm is permanently disarmed for this off-episode regardless
        // of whether the dashcam comes back later.
        //
        // Once disarmed, the rider has packed up the bike for now;
        // re-arming requires the next ride (radar power-on then off).
        val snapshot = _radarLinkState.value
        val offAt = snapshot.radarOffSinceMs
        if (offAt != null && snapshot.walkAwayArmed) {
            val slug = resolveDashcamSlug()
            val lastAdvert = slug?.let { BatteryStateBus.entries.value[it] }?.lastSeenElapsedMs ?: Long.MIN_VALUE / 2
            val anchorMs = maxOf(offAt, lastAdvert)
            val freshMs = WalkAwayDecider.Config(
                enabled = false,
                thresholdMs = 0,
            ).dashcamFreshMs
            if (nowMs - anchorMs > freshMs) {
                // Conditional disarm: if a concurrent markConnected
                // arrived between the snapshot above and this update, the
                // off-episode that motivated BLANK is already over and
                // walkAwayArmed has been cleared / a fresh episode may have
                // begun with a new offAt. Only disarm when the cluster is
                // still on the same off-episode we observed.
                _radarLinkState.update { current ->
                    if (current.walkAwayArmed && current.radarOffSinceMs == offAt) {
                        current.copy(walkAwayArmed = false)
                    } else {
                        current
                    }
                }
                // Off-episode resolved (rider has packed up): free the ride
                // wakelock - the drop cue / walk-away timers no longer need
                // guaranteed on-time ticks.
                releaseRideWakeLock()
                clog(
                    "# walkaway state=BLANK transition_reason=dashcam-stale " +
                        "window_ms=${nowMs - anchorMs} fresh_ms=$freshMs",
                )
            }
        }
    }

    fun evaluateWalkAway(nowMs: Long) {
        val slug = resolveDashcamSlug()
        // Not-seen sentinel: Long.MIN_VALUE/2 (not 0L) so a dashcam never seen
        // this session reads as unconditionally stale under the monotonic clock
        // (0L is the boot instant, which could look "fresh" early in a session).
        // Defensive/future-proof: WalkAwayDecider's 60 s cold-start + 30 s
        // off-threshold gates mean a fire is never evaluated before ~90 s of
        // uptime, where even 0L is already stale - but the sentinel keeps the
        // not-seen semantics correct if those constants ever shrink.
        val dashcamLastAdvertMs = slug?.let { BatteryStateBus.entries.value[it] }?.lastSeenElapsedMs ?: Long.MIN_VALUE / 2
        val link = _radarLinkState.value
        val input = WalkAwayDecider.Input(
            nowMs = nowMs,
            config = WalkAwayDecider.Config(
                // Gate on both the dedicated toggle AND the dashcam-warn
                // master switch. If the rider explicitly said "don't
                // warn me about the dashcam at all" we respect that.
                enabled = prefs.walkAwayAlarmEnabled &&
                    prefs.dashcamWarnWhenOff &&
                    slug != null,
                thresholdMs = prefs.walkAwayAlarmThresholdSec * 1000L,
            ),
            // Snapshot the cluster once so the decider sees a coherent set
            // of fields rather than a sequence of independent volatile reads.
            radarConnected = link.radarGattActive,
            radarOffSinceMs = link.radarOffSinceMs,
            dashcamLastAdvertMs = dashcamLastAdvertMs,
            armed = link.walkAwayArmed,
            sessionTotalRadarConnectedMs = link.sessionRadarConnectedMs,
            lastFireMs = link.lastWalkAwayFireMs,
            dismissedForEpisode = link.walkAwayDismissed,
        )
        when (WalkAwayDecider.decide(input)) {
            WalkAwayDecider.Action.FIRE -> {
                postWalkAwayNotification()
                startWalkAwayAlarm()
                _radarLinkState.update { it.copy(lastWalkAwayFireMs = nowMs) }
            }
            WalkAwayDecider.Action.AUTO_DISMISS -> {
                cancelWalkAwayNotification()
                stopWalkAwayAlarm()
                _radarLinkState.update { it.copy(lastWalkAwayFireMs = null) }
            }
            WalkAwayDecider.Action.NONE -> {}
        }
    }

    /**
     * Radar-drop audio cue: a dropped radar link looks identical to a clear
     * road on the overlay, and the rider's eyes are on the road, so the
     * warning must be audible. Fires when the radar has been down for
     * [RADAR_DROP_THRESHOLD_MS] while riding is confirmed, then repeats every
     * [RADAR_DROP_CUE_INTERVAL_MS] - until reconnect on the live-eBike path,
     * capped at [RadarDropDecider.MAX_LATCH_ONLY_CUES] per off-episode on the
     * latch-only path, and vetoed outright by a last-known-locked bike (see
     * [RadarDropDecider.ridingConfirmed]).
     *
     * Riding-confirmed via EITHER a fresh eBike `system_locked == false` OR the
     * radar-activity latch ([radarActivityFreshAtDrop], sampled at the drop):
     * the rider was moving above walking pace just before the link dropped, or
     * - where the radar cannot report that and no eBike can either - a vehicle
     * was reported just before it. The
     * eBike path is mutually exclusive with the walk-away alarm; the
     * radar-activity path reaches the radar-only / no-eBike rider without a
     * ride-end false fire (a dismount's speed has been ~0 since the stop, so the
     * latch is stale) but is deliberately NOT exclusive with walk-away arming -
     * see the WALK-AWAY EXCLUSIVITY note in [RadarDropDecider]. Full design
     * rationale + scenario matrix there too.
     */
    fun evaluateRadarDrop(nowMs: Long) {
        val link = _radarLinkState.value
        val downForMs = link.radarOffSinceMs?.let { nowMs - it }
        val snap = eBikeSnapshot()
        val ebikeAgeMs = nowMs - eBikeSnapshotAtMs()
        // Dead-radar banner: cohort-aware + bounded (see RadarLinkVisualDecider).
        // eBike riders -> "...but bike unlocked" while unlocked, hidden once the
        // bike is locked, capped by a forgot-to-lock backstop; radar-only ->
        // plain message, retires after the short cap unless the rider opted into
        // persistence. Locked is STICKY regardless of snapshot freshness: locking
        // the bike is itself what makes it sleep and drop the eBike link, so the
        // lock reading inevitably ages out - a freshness gate here mis-reads a
        // just-parked bike as "unlocked" for minutes (the reported bug). A riding
        // rider is never last-known-locked (the bike doesn't sleep while moving),
        // so this can't hide the banner mid-ride; only a last-known-UNLOCKED stale
        // snapshot keeps the banner up, which is the ambiguous mid-ride Flow+radar
        // dropout the banner must survive. Must run before the isPaused
        // early-return so a pause HIDES the banner (decide() returns LIVE when
        // paused); the eager hide on reconnect lives in markConnected.
        val explicitParked = snap?.systemLocked == true
        // Ride-over reset. An explicit lock ends the ride, so this off-episode's
        // cue latch must not survive into the next power-on: the latch is what
        // arms the reconnect acknowledgement pulse, and a stale one would fire it
        // when TOMORROW's radar link comes up - a lone beep acknowledging a drop
        // the rider heard yesterday. The off-episode itself (radarOffSinceMs)
        // deliberately survives; only the cue bookkeeping is closed out.
        if (explicitParked) {
            radarDropLastCueMs = null
            radarDropCueCount = 0
        }
        val visual = RadarLinkVisualDecider.decide(
            radarEverLive = link.sessionRadarConnectedMs > 0L,
            everSawTrack = everSawTrack(),
            radarDownForMs = downForMs,
            visualThresholdMs = RADAR_DROP_VISUAL_THRESHOLD_MS,
            paused = prefs.isPaused,
            hasEBikeSignal = hasEBikeSignal(),
            explicitParked = explicitParked,
            ebikeMaxMs = RADAR_BANNER_EBIKE_MAX_MS,
            radarOnlyMaxMs = RADAR_BANNER_RADAR_ONLY_MAX_MS,
            radarOnlyPersistent = prefs.reconnectBannerPersistent,
        )
        setReconnectBanner(visual)
        if (prefs.isPaused) return
        val ridingFresh = eBikeRidingFresh(nowMs)
        // The Experimental toggle is read per tick rather than at the drop, so
        // a rider who switches it off on hearing an unwanted cue silences the
        // REST of this off-episode, not merely the next one.
        val trackConfirmed = trackFreshAtDrop && prefs.radarDropTrackFallbackEnabled
        val ridingConfirmed = RadarDropDecider.ridingConfirmed(
            systemLocked = snap?.systemLocked,
            snapshotAgeMs = ebikeAgeMs,
            freshMs = RADAR_DROP_EBIKE_FRESH_MS,
            eBikeRidingFresh = ridingFresh,
            radarActivityFreshAtDrop = radarActivityFreshAtDrop || trackConfirmed,
        )
        // Latch-only = riding is confirmed, but not by a live eBike signal:
        // only that path gets the per-episode repeat cap (a live "unlocked"
        // genuinely re-confirms riding every tick and stays uncapped).
        val liveEBikeConfirmed = RadarDropDecider.ridingConfirmed(
            systemLocked = snap?.systemLocked,
            snapshotAgeMs = ebikeAgeMs,
            freshMs = RADAR_DROP_EBIKE_FRESH_MS,
            eBikeRidingFresh = ridingFresh,
        )
        val decision = RadarDropDecider.decide(
            radarEverLive = link.sessionRadarConnectedMs > 0L,
            radarDownForMs = downForMs,
            ridingConfirmed = ridingConfirmed,
            nowMs = nowMs,
            thresholdMs = RADAR_DROP_THRESHOLD_MS,
            cadenceMs = RADAR_DROP_CUE_INTERVAL_MS,
            lastCueMs = radarDropLastCueMs,
            latchOnlyConfirmation = ridingConfirmed && !liveEBikeConfirmed,
            cueCount = radarDropCueCount,
        )
        // The latch resets lazily here on the next tick that sees the radar
        // back up (downForMs == null), NOT eagerly in markConnected like
        // the walk-away state. Safe because a fresh drop re-stamps
        // radarOffSinceMs and restarts below the threshold.
        radarDropLastCueMs = decision.lastCueMs
        radarDropCueCount = decision.cueCount
        if (decision.fire) {
            alertBeeper()?.playRadarDropped()
            clog(
                "# radar_drop_cue down_ms=${downForMs ?: -1L} " +
                    "system_locked=${snap?.systemLocked} ebike_age_ms=$ebikeAgeMs " +
                    "cue_count=${decision.cueCount}",
            )
        }
        // Near-miss diagnostics: an eBike IS present but the radar-down cue is
        // held because riding isn't confirmed (the snapshot went stale, or the
        // bike is locked). Log once per down-episode so the freshness gate is
        // tunable from ride logs; reset the latch when the radar returns. Gated
        // on a non-null snapshot: an eBike rider's suppression is the tunable
        // one. A range-only rider's own gate is tunable too now (the
        // track-presence window), so widening this to them is worth doing when
        // there is a report to tune it from; it is left narrow until then
        // rather than adding a line nobody reads.
        val suppressed = link.sessionRadarConnectedMs > 0L &&
            snap != null &&
            downForMs != null &&
            downForMs >= RADAR_DROP_THRESHOLD_MS &&
            !ridingConfirmed
        if (suppressed && !radarDropSuppressLogged) {
            radarDropSuppressLogged = true
            clog(
                "# radar_drop_suppressed down_ms=$downForMs reason=riding-not-confirmed " +
                    "system_locked=${snap.systemLocked} ebike_age_ms=$ebikeAgeMs",
            )
        }
        if (downForMs == null) radarDropSuppressLogged = false
        // Reconnect acknowledgement: fires once on the tick the radar comes
        // back up, but only when a drop cue had been raised this down-episode
        // (decided in [RadarDropDecider]). Closes the ambiguity a bare silence
        // leaves after a drop cue - "back" vs "still dead".
        if (decision.fireReconnect) {
            alertBeeper()?.playRadarReconnected()
            clog("# radar_reconnect_cue")
        }
        // Forgot-to-lock reminder: the rider walked off (radar down + eBike
        // snapshot stale = out of range) with the bike's last reading unlocked,
        // the case the walk-away alarm stays silent for. Fires the wrist haptic
        // once per off-episode; reset + cancelled on reconnect (markConnected).
        // Re-read the off-instant fresh so a reconnect that lands mid-tick
        // (clearing radarOffSinceMs after the snapshot above) can't fire on a
        // stale down-duration and re-latch past markConnected's eager reset.
        val ftlDownForMs = _radarLinkState.value.radarOffSinceMs?.let { nowMs - it }
        if (ForgotToLockDecider.shouldFire(
                enabled = prefs.forgotToLockAlertEnabled,
                radarEverLive = link.sessionRadarConnectedMs > 0L,
                radarDownForMs = ftlDownForMs,
                systemLocked = snap?.systemLocked,
                snapshotAgeMs = ebikeAgeMs,
                freshMs = RADAR_DROP_EBIKE_FRESH_MS,
                downThresholdMs = FORGOT_LOCK_DOWN_THRESHOLD_MS,
                alreadyFired = forgotToLockFired,
            )
        ) {
            forgotToLockFired = true
            postForgotToLock()
            clog("# forgot_to_lock_alert down_ms=$ftlDownForMs ebike_age_ms=$ebikeAgeMs")
        }
    }

    companion object {
        /** Radar-drop cue: continuous radar-off time before the first cue.
         *  Deliberately generous so the normal end-of-ride wind-down (radar
         *  off around when the dashcam goes off) never trips it. */
        const val RADAR_DROP_THRESHOLD_MS = 60_000L

        /** Dead-radar banner: continuous radar-off time before the overlay marks
         *  the rear blind. Far shorter than the audio cue's 60s because a
         *  glanceable status banner is cheap (not an interruptive nag). 10s rides
         *  through normal reconnects (corpus median 8.4s, floor 5.3s) and marks
         *  the screen only once a drop is likely real. */
        const val RADAR_DROP_VISUAL_THRESHOLD_MS = 10_000L

        /** Radar-only banner retire cap: down-duration after which the banner
         *  hides for a rider with no eBike lock signal (~30s visible past the
         *  10s threshold). Avoids a permanent overlay; the rider can opt into
         *  persistence (`Prefs.reconnectBannerPersistent`). See
         *  [RadarLinkVisualDecider]. */
        const val RADAR_BANNER_RADAR_ONLY_MAX_MS = 40_000L

        /** eBike banner forgot-to-lock backstop: down-duration after which an
         *  eBike rider's still-unlocked banner retires anyway. Generous (5 min)
         *  because the repeating audio cue keeps warning after the visual caps,
         *  and an unlocked-but-radar-down state is a useful "you forgot to lock"
         *  hint until then. See [RadarLinkVisualDecider]. */
        const val RADAR_BANNER_EBIKE_MAX_MS = 300_000L

        /** Radar-drop cue re-fire gap while the radar stays down. */
        const val RADAR_DROP_CUE_INTERVAL_MS = 180_000L

        /** Max age of the eBike snapshot for its `system_locked` to be trusted
         *  by the radar-drop cue. Older than this means the eBike link has
         *  itself dropped (rider left), so "unlocked" can't be believed. */
        const val RADAR_DROP_EBIKE_FRESH_MS = 30_000L

        /** Min radar-down time before the forgot-to-lock reminder is considered:
         *  long enough that the rider has actually walked off, not a mid-ride
         *  radar blip. See [ForgotToLockDecider]. */
        const val FORGOT_LOCK_DOWN_THRESHOLD_MS = 30_000L

        /** Same trust window for the walk-away arming gate: a `system_locked =
         *  false` older than this is a stale reading from before the eBike link
         *  dropped and must NOT suppress arming. Separate from the radar-drop
         *  constant so the two gates can be tuned independently. */
        const val WALKAWAY_EBIKE_FRESH_MS = 30_000L

        /** Bike speed (m/s) above which a radar frame counts as riding activity
         *  for the radar-only drop-cue confirmation. 2.0 m/s (7.2 km/h) sits
         *  clearly above a walk - a rider pushing a dismounted bike (~1.3 m/s)
         *  does NOT count, so the signal falls to ~0 at a dismount. One
         *  definition, shared with the eBike cohort's [RidingSpeedGate]: the two
         *  paths must not drift to different ideas of walking pace. */
        const val RADAR_DROP_WALKING_PACE_MS = RidingSpeedGate.WALKING_PACE_MS

        /** Radar-activity freshness window for the drop cue: how recently before
         *  the drop the rider must have been moving above walking pace for the
         *  radar-only cue to fire. 30 s is the largest window that stays inside
         *  the typical 30-60 s park-then-fiddle spell that precedes a deliberate
         *  power-off, so a dismount stays silent (the hard constraint). Measured
         *  on the ride-capture corpus: at 30 s this false-fires on 4 of 76
         *  genuine ride-ends (45 s hits 11, including a real 42 s
         *  fiddle-then-power-off), still catches every genuine moving drop, and
         *  leaves only stopped spells longer than 2 minutes uncovered (~1.2% of
         *  riding time - beyond any normal traffic light). Latched at the drop
         *  instant (see [RadarDropDecider.activityFreshAtDrop]). */
        const val RADAR_DROP_ACTIVITY_FRESH_MS = 30_000L

        /** Track-presence freshness window for the drop cue's fallback path:
         *  how recently before the drop a range-only radar must have reported a
         *  vehicle. Deliberately its own constant rather than a reuse of
         *  [RADAR_DROP_ACTIVITY_FRESH_MS] - the two happen to agree at 30 s and
         *  were measured separately, so tuning one must not move the other.
         *  NOTHING PINS THAT while the values agree: both are passed as a
         *  window argument to a pure function, so swapping them at the call
         *  site is undetectable by any test until one of them moves. Treat
         *  this as a maintenance instruction, not an invariant. At
         *  30 s the corpus replay opens on 6 of 76 genuine ride-ends against the
         *  speed gate's 4, and leaves the cue unreachable for 39% of riding
         *  time; 45 s takes the ride-ends to 13. See the TRACK-PRESENCE FALLBACK
         *  note in [RadarDropDecider]. */
        const val RADAR_DROP_TRACK_FRESH_MS = 30_000L

        /** Ride-wakelock acquire window: hold the CPU only if the drop looks
         *  like a live ride within this window - the rider moving above walking
         *  pace, or, on a stream with no rider speed for a rider with no eBike,
         *  a vehicle reported. The drop-cue toggle does not gate it, because
         *  this lock also protects the walk-away and ride-summary timers of a
         *  rider who switched that cue off. Wider than
         *  the cue's [RADAR_DROP_ACTIVITY_FRESH_MS] because the wakelock protects
         *  ALL the off-episode timers (walk-away, ride summary), not just the
         *  cue, so it should cover any plausibly-live off-episode; but not so
         *  wide that a radar dropping long after the rider parked acquires it. */
        const val RIDE_WAKELOCK_ACTIVITY_FRESH_MS = 120_000L

        /** Hard cap on the ride wakelock (PowerManager auto-releases at this
         *  age even if every explicit release path is missed). Sized to outlast
         *  the first drop cue at [RADAR_DROP_THRESHOLD_MS] (60 s) AND its first
         *  repeat at 60 s + [RADAR_DROP_CUE_INTERVAL_MS] (240 s), with margin for
         *  tick latency, so a wedged off-episode still delivers both before the
         *  backstop fires. Normal releases (reconnect / BLANK / ride summary)
         *  free it far sooner. Never unbounded. */
        const val RIDE_WAKELOCK_CAP_MS = 300_000L
    }
}
