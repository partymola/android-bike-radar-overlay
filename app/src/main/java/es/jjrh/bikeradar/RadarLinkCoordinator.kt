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
 * The clock is injected as [clock] (was `System.currentTimeMillis()` inline) so
 * tests can pin the off-instant, session-time integration and re-fire cadences
 * deterministically.
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
    private val setReconnectBanner: (Boolean) -> Unit,
    private val resolveDashcamSlug: () -> String?,
    private val eBikeSnapshot: () -> LiveDataSnapshot?,
    private val eBikeSnapshotAtMs: () -> Long,
    private val cancelWalkAwaySnooze: () -> Unit,
    private val clearDashcamBackoff: () -> Unit,
) : RadarLinkStateGateway {

    private val _radarLinkState = MutableStateFlow(RadarLinkState())
    val radarLinkState: StateFlow<RadarLinkState> = _radarLinkState

    // Re-fire latch for the radar-drop cue + a one-shot "suppressed" diagnostic
    // flag, both scoped to the current off-episode. Single tick-loop writer
    // ([evaluateRadarDrop]); reset lazily there on radar return.
    @Volatile private var radarDropLastCueMs: Long? = null

    @Volatile private var radarDropSuppressLogged = false

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
            clog("# walkaway state=IDLE transition_reason=radar-connected prev_state=$prevState")
        }
        // Radar is back: hide the reconnecting banner now rather than waiting
        // for the next (up to 30s idle) tick of evaluateRadarDrop.
        setReconnectBanner(false)
    }

    override fun markDisconnected() {
        val nowMs = clock()
        val prev = _radarLinkState.value
        // Computed once from prev and reused across any CAS retries below.
        // walkAwayArmed is monotonic within an off-episode (only cleared by
        // markConnected / tickWalkAwayState BLANK), so re-evaluating
        // it per retry wouldn't change the post-state semantically.
        val armOnDisconnect = prev.radarOffSinceMs == null &&
            WalkAwayArmingGate.shouldArm(
                eBikeSnapshot(),
                snapshotAgeMs = nowMs - eBikeSnapshotAtMs(),
                freshMs = WALKAWAY_EBIKE_FRESH_MS,
            )
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
        if (prev.radarOffSinceMs == null) {
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
            val lastAdvert = slug?.let { BatteryStateBus.entries.value[it] }?.readAtMs ?: 0L
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
                clog(
                    "# walkaway state=BLANK transition_reason=dashcam-stale " +
                        "window_ms=${nowMs - anchorMs} fresh_ms=$freshMs",
                )
            }
        }
    }

    fun evaluateWalkAway(nowMs: Long) {
        val slug = resolveDashcamSlug()
        val dashcamLastAdvertMs = slug?.let { BatteryStateBus.entries.value[it] }?.readAtMs ?: 0L
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
     * [RADAR_DROP_THRESHOLD_MS] while eBike confirms the rider is still on the
     * bike, then repeats every [RADAR_DROP_CUE_INTERVAL_MS] until reconnect.
     *
     * eBike-gated on purpose: a fresh `system_locked == false` is the only
     * signal that separates a mid-ride radar loss (cue) from a ride-end
     * dismount (no cue). That makes this mutually exclusive with the walk-away
     * alarm, which arms only when NOT unlocked. Without an eBike signal there is
     * no cue - a radar-only / no-eBike rider never gets a false ride-end fire.
     * Full design rationale + scenario matrix in [RadarDropDecider]'s KDoc.
     */
    fun evaluateRadarDrop(nowMs: Long) {
        val link = _radarLinkState.value
        val downForMs = link.radarOffSinceMs?.let { nowMs - it }
        // Visual "reconnecting" banner: shown whenever the rear link is down
        // past the visual threshold. NOT eBike-gated (unlike the audio cue
        // below) - it is a glanceable status and the only dead-radar signal a
        // radar-only rider gets. Hidden while paused; the eager hide on
        // reconnect lives in markConnected so it doesn't wait for the
        // (up to 30s) idle tick.
        val visual = RadarLinkVisualDecider.decide(
            radarEverLive = link.sessionRadarConnectedMs > 0L,
            radarDownForMs = downForMs,
            visualThresholdMs = RADAR_DROP_VISUAL_THRESHOLD_MS,
            paused = prefs.isPaused,
        )
        // Must run before the isPaused early-return below so a pause HIDES an
        // already-shown banner (decide() returns LIVE when paused).
        setReconnectBanner(visual == RadarLinkVisualDecider.LinkVisual.RECONNECTING)
        if (prefs.isPaused) return
        val snap = eBikeSnapshot()
        val ebikeAgeMs = nowMs - eBikeSnapshotAtMs()
        val ridingConfirmed = RadarDropDecider.ridingConfirmed(
            systemLocked = snap?.systemLocked,
            snapshotAgeMs = ebikeAgeMs,
            freshMs = RADAR_DROP_EBIKE_FRESH_MS,
        )
        val decision = RadarDropDecider.decide(
            radarEverLive = link.sessionRadarConnectedMs > 0L,
            radarDownForMs = downForMs,
            ridingConfirmed = ridingConfirmed,
            nowMs = nowMs,
            thresholdMs = RADAR_DROP_THRESHOLD_MS,
            cadenceMs = RADAR_DROP_CUE_INTERVAL_MS,
            lastCueMs = radarDropLastCueMs,
        )
        // The latch resets lazily here on the next tick that sees the radar
        // back up (downForMs == null), NOT eagerly in markConnected like
        // the walk-away state. Safe because a fresh drop re-stamps
        // radarOffSinceMs and restarts below the threshold.
        radarDropLastCueMs = decision.lastCueMs
        if (decision.fire) {
            alertBeeper()?.playRadarDropped()
            clog(
                "# radar_drop_cue down_ms=${downForMs ?: -1L} " +
                    "system_locked=${snap?.systemLocked} ebike_age_ms=$ebikeAgeMs",
            )
        }
        // Near-miss diagnostics: an eBike IS present but the radar-down cue is
        // held because riding isn't confirmed (the snapshot went stale, or the
        // bike is locked). Log once per down-episode so the freshness gate is
        // tunable from ride logs; reset the latch when the radar returns. Gated
        // on a non-null snapshot so a radar-only rider - whose cue is suppressed
        // by design, with nothing to tune - never logs it.
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
    }

    companion object {
        /** Radar-drop cue: continuous radar-off time before the first cue.
         *  Deliberately generous so the normal end-of-ride wind-down (radar
         *  off around when the dashcam goes off) never trips it. */
        const val RADAR_DROP_THRESHOLD_MS = 60_000L

        /** Visual "reconnecting" banner: continuous radar-off time before the
         *  overlay marks the rear blind. Far shorter than the audio cue's 60s
         *  because a glanceable status banner is cheap (not an interruptive
         *  nag) and is the ONLY dead-radar signal a radar-only rider gets. 10s
         *  rides through normal reconnects (corpus median 8.4s, floor 5.3s) and
         *  marks the screen only once a drop is likely real. Not eBike-gated. */
        const val RADAR_DROP_VISUAL_THRESHOLD_MS = 10_000L

        /** Radar-drop cue re-fire gap while the radar stays down. */
        const val RADAR_DROP_CUE_INTERVAL_MS = 180_000L

        /** Max age of the eBike snapshot for its `system_locked` to be trusted
         *  by the radar-drop cue. Older than this means the eBike link has
         *  itself dropped (rider left), so "unlocked" can't be believed. */
        const val RADAR_DROP_EBIKE_FRESH_MS = 30_000L

        /** Same trust window for the walk-away arming gate: a `system_locked =
         *  false` older than this is a stale reading from before the eBike link
         *  dropped and must NOT suppress arming. Separate from the radar-drop
         *  constant so the two gates can be tuned independently. */
        const val WALKAWAY_EBIKE_FRESH_MS = 30_000L
    }
}
