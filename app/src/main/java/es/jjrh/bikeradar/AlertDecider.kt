// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Selects which tier-raise ("escalation") beeps bypass the inter-beep
 * cooldown. A tier raise is genuinely new threat information (the closest
 * car got closer), unlike same-tier chatter; bypassing the cooldown fires
 * it the same frame instead of deferring it - which the cooldown can drop
 * entirely if the car overtakes before the cooldown expires. The per-tid
 * latch still suppresses same-tier re-fires under every mode, so this only
 * ever ADDS the escalation beep the cooldown would have dropped. [E1]
 *
 *  - [NONE]: pre-E1 behaviour - every beep, including a tier raise, waits
 *    out the (speed-aware) cooldown.
 *  - [ALL]: any tier raise on the closest track fires immediately.
 *  - [TOP_TIER]: only a raise INTO the near third (Beep 3, the imminent
 *    case) fires immediately; lower raises still respect the cooldown.
 */
enum class EscalationCooldownBypass { NONE, ALL, TOP_TIER }

/**
 * Which distance the awareness tiers are scored on.
 *
 *  - [TRUE_RANGE]: the hypotenuse of the target's along-axis distance and its
 *    lateral offset - how far the vehicle actually is from the rider.
 *  - [ALONG_AXIS]: the radar's rangeY alone, discarding lateral offset. A
 *    vehicle drawing abeam climbs the tiers under this as though it were
 *    closing.
 *
 * See [AlertDecider.alertDistanceM].
 */
enum class TierDistance { TRUE_RANGE, ALONG_AXIS }

/**
 * Whether the imminent-impact cue has to wait out the awareness-beep cooldown.
 *
 *  - [SHARED_WITH_BEEPS]: an urgent fire needs `minBeepGapMs` since the last
 *    audible cue of any kind, so an awareness beep can swallow it.
 *  - [EPISODE_ONLY]: the urgent fires on its own episode pacing alone.
 *
 * The two channels are meant to be separate: the awareness beeps say what is
 * around, the urgent cue says act now. Letting the first mute the second
 * inverts their priority.
 */
enum class UrgentCooldown { SHARED_WITH_BEEPS, EPISODE_ONLY }

/**
 * What the predicted-pass veto scores the vehicle against.
 *
 *  - [BIKE_ENVELOPE]: the closest the fitted path comes to the bike's
 *    centreline anywhere along the bike, from [AlertDecider.BIKE_AHEAD_OF_RADAR_M]
 *    in front of the radar to [AlertDecider.BIKE_BEHIND_RADAR_M] behind it.
 *  - [RADAR_POINT]: the fitted path's offset where it draws level with the
 *    RADAR, which is one point on a machine about 2.5 m long. Retained so a
 *    corpus replay can diff the two directly.
 *
 * The envelope is what lets the threshold be a number a rider can reason about
 * ("do not warn me about anything that will clear my bike by 1.5 m") instead of
 * an offset from a sensor. Measured over the ride corpus, the span itself moves
 * only a few cues; the working part of the change is that the threshold now
 * means something physical, and so can be exposed in Settings.
 *
 * The capture log writes the scored quantity as `min_clearance=` under both,
 * which under [RADAR_POINT] is the radar-point offset rather than a minimum
 * over anything. No production caller selects [RADAR_POINT] - it is reachable
 * only from a test - so no capture log can hold one of its lines, and nothing
 * has to tell the two apart after a ride.
 */
enum class PassScoring { BIKE_ENVELOPE, RADAR_POINT }

/**
 * Pure-JVM alert decision engine. Fed one frame at a time, returns either a
 * `Beep(urgency)` to acknowledge a new threat or a closer-distance escalation,
 * a `Clear` chime when the road empties, or `None`.
 *
 * Beep count maps to the proximity of the **closest** car in the close zone:
 *   - urgency 1  : far third of the alert window (d > 2/3 alertMaxM)
 *   - urgency 2  : middle third
 *   - urgency 3  : near third (d <= 1/3 alertMaxM)
 *
 * `d` here is [alertDistanceM] - the target's TRUE range, not the
 * along-axis `distanceM` - so a vehicle drawing abeam no longer climbs
 * the tiers as though it were closing. Both the tier and the choice of
 * which vehicle the audio describes use it; everything else (close-set
 * entry, the all-clear, the urgent-impact gates) still reads
 * `distanceM`.
 *
 * Triggers (closest-only audio model: a beep tells the rider about
 * the *closest* threat only; piling on a beep for a track that
 * doesn't change "the closest is at tier N" information is the
 * cacophony pattern):
 *  - **Closest-tier rising edge.** A new track entering the close
 *    set is silent unless its arrival raises the *closest-urgency
 *    tier* above the highest tier we have audibly fired for on the
 *    new closest track.
 *  - **Escalation with per-track tier latch.** When the closest car
 *    crosses into a higher urgency bucket (further -> closer),
 *    re-beep at the new level — but only if we haven't already
 *    audibly fired for *that tid at that tier*. Intra-tier distance
 *    jitter (e.g. 11→9→11 m flapping the near-third boundary) does
 *    NOT re-fire. The tier-raise re-beep fires the SAME frame - it
 *    bypasses the beep cooldown ([escalationBypass]) so a fast
 *    closer's escalation is never swallowed by the window. [E1]
 *  - **Filtered overtake re-acknowledgement.** When a track that
 *    was close transitions to `isBehind` and others remain close,
 *    re-beep only if the remaining closest-urgency is *strictly
 *    greater* than the peak urgency the just-overtaking track ever
 *    reached. Same-or-lower tier re-statement is silent — the rider
 *    was already alerted at that tier by the now-overtaking track.
 *  - **Sustain debounce.** A track must be present in close for
 *    `sustainFrames` consecutive frames before it counts. Single-
 *    frame radar blips never fire.
 *  - **Beep cooldown.** No two beeps within `minBeepGapMs`. Triggers
 *    in the cooldown window collapse into a single beep at the
 *    closest urgency *at the moment the cooldown expires* - except a
 *    strict tier raise, which bypasses the cooldown and fires the same
 *    frame ([escalationBypass]). The cooldown therefore now only
 *    gates same-tier re-statements and new lower-or-equal-tier entries.
 *  - **Clear chime.** Plays once when NO vehicle is physically present behind
 *    the rider in range (the raw radar read - any non-`isBehind` target within
 *    `alertMaxM`, INCLUDING alongside/parked-classified tracks, regardless of
 *    tid) AND that has held for [clearGraceMs] (different timbre, never
 *    overlaps a beep on the speaker; not gated by the beep cooldown). Gating
 *    the all-clear on RAW presence rather than the beep-path close set is
 *    deliberate: a matched-speed follower docked as `isAlongsideStationary`,
 *    or one that briefly drops and returns under a new tid, is still
 *    physically behind, so it must not produce a "road clear" chime. The grace
 *    also absorbs a single-frame dropout or boundary flap. A car genuinely
 *    leaving (overtake -> `isBehind`, or cornering / turning off) clears after
 *    the grace.
 *  - **Close-set exit hysteresis (distance band).** A track enters the
 *    close set at `distanceM <= alertMaxM` but, once in, stays until
 *    `distanceM` exceeds `alertMaxM + alertMaxM/`[CLOSE_EXIT_HYSTERESIS_DIVISOR].
 *    Stops an edge-lingering car (decoded distance jittering e.g. 30<->31 m
 *    at alertMaxM=30) from flapping out of the set. Entry threshold is
 *    unchanged - no alerting for cars first seen beyond `alertMaxM`.
 *  - **Stationary suppress.** Once the rider has been at or below
 *    `stationaryMsThreshold` for at least `stationaryDwellMs` of
 *    elapsed (monotonic) time, Beep events are mapped to None. Clear still
 *    fires. Lets the rider sit at a traffic light without beep/clear
 *    loops from the queue of stopped cars behind them.
 *  - **Imminent-impact safety override.** While stationary-suppressed
 *    (and, via the low-speed extension below, while moving slowly), an
 *    [Event.UrgentApproach] (distinct audio) fires anyway when any
 *    close vehicle satisfies either:
 *      a) **proximity gate** - at near-third proximity (`distanceM <=
 *         alertMaxM/3`) AND closing faster than
 *         [SAFETY_OVERRIDE_CLOSING_MS] (radar quantum-strict);
 *      b) **TTC gate** - TTC = `distanceM / closing` <= [TTC_GATE_SECONDS]
 *         AND closing >= [TTC_GATE_CLOSING_FLOOR_MS] AND `distanceM <=
 *         alertMaxM`. Strictly extends the proximity gate's coverage at
 *         the same closing-speed bar: at 6 m/s closing, TTC <= 3 s maps
 *         to distance <= 18 m, while the proximity gate caught only the
 *         distance <= alertMaxM/3 = 6 m subset. Earlier warning on the
 *         same threats; closing-floor filters slow-queue traffic.
 *    Catches a vehicle that isn't braking for the queue ahead - the
 *    only case where alerting a stopped rider is still useful (rider
 *    has a chance to dismount or move out of the line of impact).
 *    The closing-speed floor on the TTC gate filters slow-queue
 *    traffic merging into a stopped rider, where the driver is
 *    clearly tracking and braking.
 *  - **Urgent lateral-plausibility gates.** Both urgent disjuncts are
 *    distance+closing only, which makes them blind to WHERE the car is
 *    heading: a rider stopped beside a live lane meets a stream of
 *    vehicles that each close fast and pass metres to the side, and a
 *    car on a parallel street can satisfy the TTC arithmetic from 30 m
 *    off-axis. Ride evidence (stopped on a multi-lane road): 27 urgent
 *    cues in ~65 s, every firing track ending as a side pass.
 *    Two vetoes, both failing OPEN (no/unreliable lateral data = fire):
 *      a) **off-axis veto** - a candidate whose raw lateral offset
 *         `|rangeXm|` exceeds [URGENT_LATERAL_MAX_M] on the firing frame
 *         is not an imminent-impact threat: rear-cone traffic that can
 *         reach the rider inside the <= 3 s TTC window cannot be two
 *         lanes off-axis. Kills crossing/parallel-street artefacts.
 *         Skipped when the frame is [Vehicle.lateralUnknown].
 *      b) **predicted-pass veto** - a straight least-squares fit of the
 *         track's measured (distance, rangeXm) history extrapolates its
 *         lateral offset at distance 0 (the pass point). When the fit is
 *         confident ([URGENT_PASS_FIT_MIN_POINTS]+ sightings spanning
 *         [URGENT_PASS_FIT_MIN_SPAN_M]+ of approach) and predicts
 *         the fitted line clears the bike's centreline by at least the
 *         rider's configured margin (default [DEFAULT_PASS_CLEARANCE_M])
 *         at its CLOSEST point along the bike, the car is committed to a
 *         side pass, not an impact line. A line predicted to cross the
 *         centreline anywhere along the bike scores zero and is never
 *         vetoed, whatever the margin. Scored across the whole machine rather than
 *         at the radar, because a converging vehicle reaches the front
 *         wheel first; see [PassScoring]. The newest
 *         [URGENT_PASS_RECENT_FIT_WINDOW] samples are judged first
 *         (swerve-reactive); the full retained history is the fallback
 *         when that window bunches at one distance, which is the normal
 *         geometry at the pass point. Because that wider window holds
 *         stale approach geometry, the fallback may veto ONLY where the
 *         newest measured sample is itself off-axis - otherwise a long
 *         next-lane approach would outvote the few fresh samples of a car
 *         swinging into the rider (see [predictedPassFit]).
 *  - **Urgent episode pacing.** The urgent cue repeats while an
 *    imminent condition is held (see the trigger-site comment for the
 *    alarm-standards rationale) - but a platoon released behind a
 *    stopped rider re-satisfies the gates with a NEW track every 1-3 s,
 *    which turns "repeat while held" into a continuous alarm. Fires
 *    within one episode (qualifying targets seen with gaps under
 *    [URGENT_EPISODE_GAP_MS]) are paced at [URGENT_EPISODE_REPEAT_MS]
 *    rather than the raw beep cooldown, EXCEPT when the trigger closes
 *    at least [URGENT_NEW_THREAT_CLOSING_DELTA_MS] faster than anything
 *    already observed this episode - genuinely new severity fires
 *    immediately (IEC 60601-1-8 "paused with new-condition override",
 *    the pattern the audio design already follows). The first fire of
 *    an episode is never delayed.
 *  - **Turn-aware clear deferral.** Cornering sweeps the
 *    radar's rear cone off every followed car, so mid-turn the stream
 *    reads empty while the road is not. While [TurnStateDecider] reports
 *    TURNING and a close episode is active, the all-clear is deferred:
 *    the deferral spans the rider's whole transit of the corner, so
 *    corner sharpness and length are accounted for automatically. When
 *    the rider straightens (HOLD) with the road still reading empty, the
 *    deferral extends once by an adaptive tail - the last-seen follower
 *    distance divided by rider speed, i.e. the time the car needs to
 *    traverse the same corner the rider just did, offset by its
 *    following distance (clamped to [TURN_TAIL_MIN_MS]..
 *    [TURN_TAIL_MAX_MS]). A car that fails to reappear within the tail
 *    has genuinely turned off, and the deferred Clear then fires after
 *    the normal grace. Beeps are NOT muted: when the radar re-finds the
 *    follower after the corner, the reacquisition beep fires - the
 *    rider wants the re-anchor. Only the false "road clear" mid-corner
 *    is suppressed; a delayed all-clear is deliberately preferred over
 *    a false one.
 *  - **Low-speed urgent extension.** When `urgentLowSpeedEnabled`
 *    (Settings toggle, default on), the same override is also
 *    evaluated while the rider is MOVING at or below
 *    [URGENT_MOVING_MAX_KMH], with both disjuncts' closing floor
 *    raised to [URGENT_MOVING_CLOSING_FLOOR_MS]. The rider-speed gate
 *    is justified by relative-doppler semantics, not "escape options":
 *    decoded closing speed is measured relative to the rider, so the
 *    same floor means a much faster absolute vehicle when the rider is
 *    slow. At the stationary 6 m/s floor the moving extension also
 *    caught routine 35-45 km/h overtakes of a slow rider (corpus
 *    replay over 105 rides: ~1 extra urgent episode per ride);
 *    the 10 m/s moving floor keeps only the genuinely fast closers
 *    (~4-5 episodes/week) including the motivating
 *    decelerating-into-junction case.
 *
 * Threading: instances are not thread-safe; serialise calls (the radar
 * stream is naturally single-producer).
 */
class AlertDecider(
    private val sustainFrames: Int = 2,
    /** Minimum elapsed (monotonic) milliseconds between two audible beeps.
     *  The closest-only trigger rule already filters multi-track
     *  noise; this cooldown is for back-to-back triggers on the
     *  closest track itself (e.g. tier raise immediately after a
     *  new-entry fire). */
    private val minBeepGapMs: Long = 700,
    /** Rider's bike speed (m/s) at or below this counts as "stationary".
     *  0.5 m/s catches raw bytes 0..2 inclusive (0, 0.25, 0.5 m/s),
     *  matching the prior 2 km/h gate exactly. */
    private val stationaryMsThreshold: Float = 0.5f,
    /** Elapsed (monotonic) milliseconds the rider's bike speed must stay at or
     *  below [stationaryMsThreshold] continuously before Beep events
     *  get mapped to None. Long enough to skip rolling stops mid-turn,
     *  short enough to kick in at a normal traffic-light stop. */
    private val stationaryDwellMs: Long = 2_000L,
    /** Elapsed (monotonic) milliseconds that NO vehicle may be physically
     *  present behind the rider in range before a Clear chime fires. Presence
     *  is the raw radar read - any non-`isBehind` target within `alertMaxM`
     *  (plus the exit band), INCLUDING alongside/parked-classified tracks and
     *  regardless of track id - decoupled from the beep-path `close` set on
     *  purpose, so a matched-speed follower that gets docked as
     *  `isAlongsideStationary`, or one that briefly drops and returns under a
     *  new tid, cannot fire a false all-clear while it is still physically
     *  behind. The grace also absorbs a single-frame radar dropout / boundary
     *  flap. A genuine all-clear (the car overtakes -> `isBehind`, or corners /
     *  turns off and leaves the stream) is delayed by at most this long: a
     *  delayed or absent all-clear is deliberately preferred over a false one,
     *  since a false all-clear tells the rider the road is clear while a car is
     *  still there. 2000 ms is the owner-chosen dwell; in the ride-capture
     *  replay it lowered the all-clear count (fewer false clears) with the
     *  imminent-impact cue tally unchanged. */
    private val clearGraceMs: Long = 2_000L,
    /** E1: which tier-raise beeps bypass the inter-beep cooldown and fire
     *  the same frame. See [EscalationCooldownBypass]. Default [ALL] - a
     *  corpus replay over 87 rides (345k frames) showed it recovers ~12
     *  cooldown-dropped escalations and advances ~54 by a median 0.6 s for
     *  only +9 net beeps; [NONE]/[TOP_TIER] are retained for re-validation. */
    private val escalationBypass: EscalationCooldownBypass = EscalationCooldownBypass.ALL,
    /** Which distance the awareness tiers are scored on. Default
     *  [TierDistance.TRUE_RANGE]; [TierDistance.ALONG_AXIS] is the shipped
     *  pre-change behaviour, retained so a corpus replay can diff the two
     *  event streams directly rather than by rebuilding an old tree. */
    private val tierDistance: TierDistance = TierDistance.TRUE_RANGE,
    /** Whether the imminent-impact cue waits out the awareness-beep cooldown.
     *  See [UrgentCooldown]. [UrgentCooldown.SHARED_WITH_BEEPS] is the shipped
     *  pre-change behaviour, retained for re-validation. */
    private val urgentCooldown: UrgentCooldown = UrgentCooldown.EPISODE_ONLY,
    /** What the predicted-pass veto scores against. See [PassScoring];
     *  [PassScoring.RADAR_POINT] is the pre-change behaviour, retained so the
     *  corpus can be replayed against both. */
    private val passScoring: PassScoring = PassScoring.BIKE_ENVELOPE,
    /** Diagnostic hook: invoked once per turn when the adaptive
     *  clear-deferral tail is anchored, with the computed tail length.
     *  The pipeline writes it to the capture log so every deferral is
     *  directly auditable post-ride instead of inferred from turn-state
     *  and alert lines. Fires at most once per corner (the anchor-once
     *  guard), so there is no flood risk. */
    private val onTurnDefer: (tailMs: Long) -> Unit = {},
    /** Diagnostic hook for the ghost-beep filter: one line per
     *  gate decision (suppress / re-fire / off-axis veto), written to the
     *  capture log so every silenced or re-armed cue is auditable
     *  post-ride. Fires only on would-have-beeped frames, so volume is
     *  bounded by the beep rate, not the frame rate. */
    private val onGateEvent: (String) -> Unit = {},
    /** Closing-evidence admission for born-close tracks (the ghost-beep
     *  filter's state machine); injectable for tests. */
    private val bornCloseGate: BornCloseGate = BornCloseGate(),
) {

    sealed class Event {
        /** Closest stable target's `lateralPos` is carried on each
         *  Beep so audio consumers can pan to the threat's side
         *  (experimental flag). `0f` when no directional information
         *  is available; consumers treat that as centred. */
        data class Beep(val count: Int, val lateralPos: Float = 0f) : Event()
        object Clear : Event()

        /** Imminent-impact override: the rider is stopped - or, with the
         *  low-speed extension on, still moving at or below
         *  [URGENT_MOVING_MAX_KMH] - AND a close vehicle satisfies
         *  either the proximity gate (near-third distance + closing
         *  past [SAFETY_OVERRIDE_CLOSING_MS]) or the TTC gate (TTC <=
         *  [TTC_GATE_SECONDS] + closing >= [TTC_GATE_CLOSING_FLOOR_MS]);
         *  moving fires demand [URGENT_MOVING_CLOSING_FLOOR_MS] on both
         *  gates. Audible regardless of the suppress dwell; the audio
         *  is intentionally distinct from a normal Beep so the rider
         *  knows this is the impact-warning case. See the class KDoc
         *  for the full gate semantics.
         *  `lateralPos` is the triggering vehicle's lateral position
         *  for directional audio (experimental flag); `0f` when not
         *  available. `viaMovingPath` records which gate opened - the
         *  low-speed moving extension vs the stationary path - so the
         *  capture log can attribute each fire for threshold tuning;
         *  the audio cue is identical either way. The `trigger*` fields
         *  carry the vehicle that actually opened the gate: the closest
         *  frame vehicle a capture log records is often a different,
         *  slower car, which made field urgents unauditable - a hidden
         *  fast closer and a false positive looked identical. */
        data class UrgentApproach(
            val lateralPos: Float = 0f,
            val viaMovingPath: Boolean = false,
            val triggerTid: Int = -1,
            val triggerDistanceM: Int = -1,
            val triggerClosingMs: Float = 0f,
            val triggerRangeXm: Float = 0f,
        ) : Event()
        object None : Event()
    }

    private val consecutiveClose = HashMap<Int, Int>()

    /** Grace-frame hysteresis state: tid -> consecutive frames the track has
     *  been ABSENT from the close set since it was last seen. A track that has
     *  ALREADY reached [sustainFrames] survives a single missed frame (one
     *  dropped BLE notification) with its [consecutiveClose] counter intact, so
     *  it fires immediately on its return frame; [GRACE_MISS_FRAMES] consecutive
     *  misses reset it. A sub-sustain track is NOT preserved - it resets the
     *  moment it leaves - so an alternating/flickering track never accrues to a
     *  beep (preserving the anti-flicker guard). Without the stable-track grace,
     *  one missed notification re-armed the sustain and delayed a deserved tier
     *  fire by ~one frame (~200 ms) - measured on 24/115 capture replays of
     *  edge-flickering vehicles. [W8] */
    private val framesSinceLastSeen = HashMap<Int, Int>()
    private var prevStableClose: Set<Int> = emptySet()
    private var prevClosestUrgency: Int = 0
    private var lastBeepAtMs: Long = Long.MIN_VALUE / 2 // guarantees first beep fires
    private var beepPending: Boolean = false

    /** Clear-grace state: whether a Clear is deferred pending the
     *  [clearGraceMs] dwell with no vehicle physically present behind, and
     *  when that "behind is empty" condition started. */
    private var clearPending: Boolean = false
    private var clearPendingSinceMs: Long = 0L

    /** True once a stable-close track has been confirmed during the current
     *  approach episode; reset when the Clear finally fires. Needed because a
     *  matched-speed follower docked as `isAlongsideStationary` keeps
     *  `stableClose` (and hence `prevStableClose`) empty for many frames while
     *  it is still physically behind, so the "arm the Clear" edge cannot rely
     *  on `prevStableClose` being non-empty at the moment the car finally
     *  leaves. This latch survives the docked window so the all-clear still
     *  fires once the car is truly gone. */
    private var closeEpisodeActive: Boolean = false

    /** Raw in-front, in-range track ids from the previous frame (post
     *  distance-exit-band), used to apply the band's exit hysteresis. */
    private var prevCloseRaw: Set<Int> = emptySet()

    /** Raw behind-in-range track ids from the previous frame (non-`isBehind`,
     *  within `alertMaxM` + exit band, alongside INCLUDED), used to apply the
     *  exit-band hysteresis to the raw-presence gate that drives the Clear. */
    private var prevBehindRaw: Set<Int> = emptySet()

    /**
     * The vehicle the last [decide] call scored the awareness tier on, and its
     * [alertDistanceM].
     *
     * Exposed so the capture log can attribute a fired cue to the track that
     * actually set it. The log's `frame_closest_*` fields are the nearest car
     * by along-axis distance, which since tiers moved to true range is no
     * longer necessarily the same vehicle - and the case where they diverge is
     * exactly the off-axis one worth reviewing after a ride. Without this a
     * tier decision is unauditable precisely when it matters.
     *
     * Read contract: valid only for the event returned by the [decide] call
     * that set them, read on the same thread before another [decide] runs.
     * Every path through [decide] assigns both, so they cannot carry a
     * previous frame's values, but they are not live state and nothing else
     * should treat them as such.
     */
    internal var lastTierTrigger: Vehicle? = null
        private set

    internal var lastTierDistanceM: Float = -1f
        private set

    /**
     * Per-track tier latch - tid -> urgency tier we have *audibly*
     * fired for during this approach episode.
     *
     * Used by both the new-entry gate and the escalation gate to
     * suppress same-tier re-fires. Once we've played a Beep(N)
     * attributable to tid T, subsequent frames where T is still the
     * closest at tier N (or lower) are silent. A re-fire on T
     * requires either:
     *   - a true tier raise above the latched tier for that tid, or
     *   - the all-clear (Clear), which clears this map.
     *
     * De-escalation does not rearm the latch: a tid that drops a full
     * tier and climbs back to N stays silent. `a spike after the top
     * tier has fired stays silent on recovery` pins it. (The born-close
     * gate's admission refire also removes a tid's entry, but a gated
     * track never fired audibly, so that path cannot unlatch a beep
     * the rider actually heard.)
     */
    private val firedTierPerTid = HashMap<Int, Int>()

    /**
     * Per-track peak-urgency tracker — tid -> highest urgency tier
     * observed for that tid since it entered the close set.
     *
     * Drives the filtered-overtake-reack gate: when a tid flips
     * isBehind, the remaining closest's urgency must be strictly
     * greater than `peakUrgencyPerTid[overtakenTid]` to fire. That is
     * its only consumer.
     *
     * Cleared on Clear, same as [firedTierPerTid].
     */
    private val peakUrgencyPerTid = HashMap<Int, Int>()

    /** Distance (m) of the closest vehicle physically behind on the most
     *  recent frame where any was present. Sizes the post-turn clear-
     *  deferral tail: the follower's catch-up time is its following
     *  distance over the rider's speed. Refreshed every behind-present
     *  frame, so at the moment the turn blacks the stream out it holds
     *  the followed car's last-seen distance. */
    private var lastBehindDistanceM: Int = 0

    /** Monotonic deadline (ms) until which the pending all-clear stays
     *  deferred after a turn - the adaptive tail anchored once per turn
     *  on the first HOLD frame with the road still reading empty.
     *  [Long.MIN_VALUE] = no tail active. Reset whenever a vehicle is
     *  present behind (the follower reappeared; normal clear semantics
     *  resume), when the deferred Clear finally fires, and on [reset]. */
    private var turnDeferUntilMs: Long = Long.MIN_VALUE

    /** Monotonic (elapsedRealtime) ms of the most recent `decide()` call in which the rider
     *  was NOT at or below [stationaryMsThreshold]. Compared against
     *  `nowMs` each call to decide whether the stationary dwell has been
     *  satisfied. [NOT_INITIALIZED] until the first call of this session. */
    private var lastNotStationaryAtMs: Long = NOT_INITIALIZED

    /** One (monotonic ms, distanceM, rangeXm) sighting for the
     *  predicted-pass veto's per-track fit. */
    private data class LateralSample(val atMs: Long, val distanceM: Int, val rangeXm: Float)

    /** tid -> recent lateral sightings, newest last, used by the
     *  predicted-pass veto. Appended for every non-`isBehind`,
     *  non-[Vehicle.lateralUnknown] sighting each frame, capped at
     *  [URGENT_PASS_HISTORY_MAX] samples. A gap longer than
     *  [URGENT_PASS_HISTORY_RESET_MS] clears the deque before appending:
     *  radar tids are recycled, and a stale run bleeding into a new car's
     *  fit would corrupt the extrapolation. Entries idle past the reset
     *  window are dropped wholesale each frame for the same reason. */
    private val lateralHistory = HashMap<Int, ArrayDeque<LateralSample>>()

    /** tid -> the last pass-gate VERDICT recorded for that track (a coarse
     *  tag, never the formatted line: see [logPassGate]). The gate is
     *  evaluated inside the urgent-candidate search on every frame, so only
     *  a CHANGE in its verdict is worth writing to the capture log. Bounded
     *  by the radar's single-byte tid space, cleared in [reset], and pruned
     *  with [lateralHistory], whose lifetime it shadows. */
    private val passGateLogged = HashMap<Int, String>()

    /** tid -> fit windows whose confident-PASS verdict has been recorded.
     *  Deliberately NOT routed through [passGateLogged], which holds one
     *  verdict per track: a fit hovering at the margin would then alternate
     *  pass/veto and emit a line per transition, and because that slot is
     *  shared it would also re-arm the off-axis and corroboration verdicts,
     *  which are deduped today. Kept separate, the pass verdict costs one
     *  line per track per fit window, and a pass cannot re-arm any of the
     *  three - the pass path never writes [passGateLogged] at all. The veto
     *  half of that is pinned by "a fit oscillating across the margin does
     *  not log once a crossing", which would see two veto lines if a pass
     *  reset the slot; the off-axis and corroboration halves hold by the
     *  same construction but have no test, because a track cannot be both
     *  inside the margin and beyond the off-axis bar on one frame. Pruned
     *  wherever [passGateLogged] is - all three sites, two of which sit
     *  inside [updateLateralHistory]. */
    private val passGateOkLogged = HashMap<Int, MutableSet<String>>()

    /** Monotonic ms an urgent-QUALIFYING target (both kinematic gates and
     *  the lateral vetoes passed) was last present. Two qualifying
     *  sightings closer together than [URGENT_EPISODE_GAP_MS] belong to
     *  the same urgent episode; a longer quiet gap starts a fresh one. */
    private var urgentLastQualifyingSeenMs: Long = NOT_INITIALIZED

    /** Monotonic ms of the last audible [Event.UrgentApproach], paced by
     *  [URGENT_EPISODE_REPEAT_MS] within an episode. Independent of
     *  [lastBeepAtMs], which every beep flavour shares. */
    private var urgentLastFireMs: Long = NOT_INITIALIZED

    /** Fastest closing speed (m/s, positive) among this episode's
     *  triggering candidates at their fire/suppress checks. A new
     *  candidate must beat it by [URGENT_NEW_THREAT_CLOSING_DELTA_MS] to
     *  bypass the episode pacing. */
    private var urgentEpisodePeakClosing: Float = 0f

    /** True once this episode has produced an audible urgent fire; until
     *  then the pacing does not apply (the first warning is never
     *  delayed). */
    private var urgentFiredThisEpisode: Boolean = false

    fun decide(
        vehicles: List<Vehicle>,
        alertMaxM: Int,
        nowMs: Long,
        bikeSpeedMs: Float? = null,
        bikeNotDriving: Boolean? = null,
        climbing: Boolean = false,
        urgentLowSpeedEnabled: Boolean = true,
        turnState: TurnStateDecider.State = TurnStateDecider.State.IDLE,
        /** Clearance the predicted pass must keep, in metres from the bike's
         *  centreline. NOT clamped here - [data.Prefs] is the only production
         *  source and clamps to [MIN_PASS_CLEARANCE_M]..[MAX_PASS_CLEARANCE_M]
         *  on both read and write. A value below that range only widens the
         *  veto; a predicted hit still fires at any margin. */
        passClearanceM: Float = DEFAULT_PASS_CLEARANCE_M,
    ): Event {
        // Rider-stationary gate. Track when the rider was last observed NOT
        // stationary; once that was more than stationaryDwellMs ago, Beep
        // events get mapped to None (Clear still fires). On the very first
        // call we initialise lastNotStationaryAtMs to nowMs so the dwell is
        // measured from now, not from zero.
        //
        // Stationary signal precedence: when the eBike snapshot reports
        // `bike_not_driving` (Bosch eBike wheel-speed ground truth), it
        // wins outright; the wheel sensor is sub-second and faster than
        // the radar's own bike-speed field. Radar-reported `bikeSpeedMs`
        // (from the V2 device-status frame) is the fallback when eBike
        // is absent (no eBike, flag off, or pre-bond). Both null = no
        // signal, treated as "not below" so the dwell never triggers.
        //
        // Climb override: when the rider is grinding up a hill
        // (rider_power sustained above threshold via ClimbDetector), the
        // stationary gate is forced off regardless of speed. A 5 km/h
        // climb up Fitzjohns Avenue is not a traffic-light stop; the
        // rider is exposed to overtaking traffic and must still get
        // alerts. The override is non-stateful here; the climb-state
        // accumulator lives in the caller (BikeRadarService).
        val isBelowThreshold = when {
            climbing -> false
            bikeNotDriving != null -> bikeNotDriving
            else -> bikeSpeedMs != null && bikeSpeedMs <= stationaryMsThreshold
        }
        if (lastNotStationaryAtMs == NOT_INITIALIZED || !isBelowThreshold) {
            lastNotStationaryAtMs = nowMs
        }
        val timeBelowStationaryMs = nowMs - lastNotStationaryAtMs
        val riderStationary = timeBelowStationaryMs >= stationaryDwellMs
        // Short mini-dwell for the imminent-impact override (below).
        // The 2 s `stationaryDwellMs` exists to skip rolling stops
        // mid-turn for the ordinary-Beep suppress path; the override
        // is much narrower (closing ≥ 6 m/s AND distance ≤ alertMaxM/3)
        // and doesn't need the same protection. 500 ms is long enough
        // to absorb single-frame radar speed noise and 200-400 ms
        // mid-turn speed dips, short enough that a rider decelerating
        // into a junction with a closing vehicle gets the urgent tone
        // well within the time-to-collision window.
        val riderBelowStationaryForUrgent =
            timeBelowStationaryMs >= URGENT_OVERRIDE_DWELL_MS

        // Skip alongside-stationary tracks (parked car / queued traffic next
        // to a crawling rider). The decoder gates these on rider speed +
        // dwell time + zero closing speed, so they are by construction
        // not threats - beeping for them would be the audio equivalent of
        // the chevron-overlap problem the visual dock was added to fix.
        //
        // Exit hysteresis on the distance gate: a track enters the close set
        // at distanceM <= alertMaxM, but once in stays until distanceM
        // exceeds alertMaxM + band. Stops a car lingering at the alertMaxM
        // edge (decoded distance jittering across the boundary) from
        // flapping out and firing a premature Clear. Entry is unchanged.
        val rangeBand = alertMaxM / CLOSE_EXIT_HYSTERESIS_DIVISOR
        val close = vehicles.filter {
            if (it.isBehind || it.isAlongsideStationary) return@filter false
            it.distanceM in 0..alertMaxM ||
                (it.id in prevCloseRaw && it.distanceM in 0..(alertMaxM + rangeBand))
        }
        val behindTids = vehicles.filter { it.isBehind }.mapTo(HashSet()) { it.id }
        val currentCloseTids = close.mapTo(HashSet()) { it.id }

        // Raw physical-presence gate for the all-clear, decoupled from the
        // beep-path `close` set above. A non-isBehind target within range -
        // INCLUDING one docked as isAlongsideStationary, and regardless of tid
        // - means a car is still physically behind, so the road is NOT clear.
        // Mirrors close's range + exit-band test but drops only the isBehind
        // exclusion (alongside is kept), with its own prev set so an alongside
        // car lingering at the band edge still gets the hysteresis.
        val behindPresentVehicles = vehicles.filter {
            if (it.isBehind) return@filter false
            it.distanceM in 0..alertMaxM ||
                (it.id in prevBehindRaw && it.distanceM in 0..(alertMaxM + rangeBand))
        }
        val behindPresent = behindPresentVehicles.isNotEmpty()
        val behindPresentTids = behindPresentVehicles.mapTo(HashSet()) { it.id }

        // Update consecutive-frame counters with grace-frame hysteresis. A tid
        // seen this frame increments its sustain counter and resets its miss
        // count. A tid that is absent this frame but has ALREADY reached
        // [sustainFrames] keeps its counter across fewer than [GRACE_MISS_FRAMES]
        // consecutive misses, so a stable edge-track that drops a single BLE
        // notification fires immediately on its return frame instead of
        // re-arming the sustain. A still-building (sub-sustain) tid resets the
        // moment it leaves, so an alternating/flickering track can never
        // accumulate to a beep. Only the stable case is preserved on purpose:
        // grace must help a confirmed track survive a blip, never let noise
        // climb to the sustain threshold. [W8]
        val updated = HashMap<Int, Int>()
        val updatedMisses = HashMap<Int, Int>()
        for (tid in currentCloseTids) {
            updated[tid] = (consecutiveClose[tid] ?: 0) + 1
        }
        for ((tid, count) in consecutiveClose) {
            if (tid in currentCloseTids) continue
            if (count < sustainFrames) continue // sub-sustain: reset on the gap (no flicker accrual)
            val misses = (framesSinceLastSeen[tid] ?: 0) + 1
            if (misses < GRACE_MISS_FRAMES) {
                updated[tid] = count
                updatedMisses[tid] = misses
            }
        }
        consecutiveClose.clear()
        consecutiveClose.putAll(updated)
        framesSinceLastSeen.clear()
        framesSinceLastSeen.putAll(updatedMisses)

        val stableClose = close.filter { (consecutiveClose[it.id] ?: 0) >= sustainFrames }
        val stableTids = stableClose.mapTo(HashSet()) { it.id }
        // Selection and scoring must use the SAME distance. Picking the
        // closest by rangeY while tiering on true range lets an off-axis
        // track mask a real one: a ghost at ry 8 / rx 9 wins `minByOrNull`
        // over a car dead behind at ry 9.5, then scores tier 2 on its 12.0 m
        // true range - and because audio voices the closest vehicle only, the
        // real car's tier-3 escalation never sounds.
        val closestVehicle = stableClose.minByOrNull { alertDistanceM(it) }
        val closestUrgency = closestVehicle
            ?.let { urgencyFor(alertDistanceM(it), alertMaxM) }
            ?: 0
        lastTierTrigger = closestVehicle
        lastTierDistanceM = closestVehicle?.let { alertDistanceM(it) } ?: -1f

        // Update peak urgency per tid for every stable-close track. Used by
        // the D2b filtered overtake re-ack gate. Must be updated BEFORE the
        // trigger gate since D2b reads `peakUrgencyPerTid[overtakenTid]`.
        for (v in stableClose) {
            val u = urgencyFor(alertDistanceM(v), alertMaxM)
            val prevPeak = peakUrgencyPerTid[v.id] ?: 0
            if (u > prevPeak) peakUrgencyPerTid[v.id] = u
        }

        // Ghost-beep filter: tier Beeps whose trigger track was BORN inside
        // [BornCloseGate.BORN_CLOSE_MAX_M] stay silent until the track shows
        // closing evidence ([BornCloseGate] admission paths), and Beeps whose
        // trigger sits beyond [RX_ABSURD_M] of raw lateral are vetoed
        // outright (the veto sites below). Beep-path ONLY by construction:
        // the all-clear presence gate, urgent evaluation, sustain counters,
        // and the overlay never see the gate.
        //
        // This block accrues closing evidence for born-close tracks and
        // re-arms the beep path for any track admitted this frame whose cue
        // was previously silenced - the "car finally started closing" beep,
        // delivered at the track's CURRENT tier through the normal emission
        // machinery (cooldown and stationary gates still apply).
        for (tid in bornCloseGate.update(vehicles, turnState, nowMs)) {
            if (tid !in stableTids) continue
            firedTierPerTid.remove(tid)
            beepPending = true
            onGateEvent("# gate refire tid=$tid")
        }

        val newEntries = stableTids - prevStableClose
        val overtakes = prevStableClose intersect behindTids

        // Trigger gate. Audio describes the closest threat only;
        // additional tracks at the same or lower tier are silent.
        //
        //   Closest-tier rising edge: a new track entering the close
        //     set fires only if its arrival raises the closest-urgency
        //     tier above what we have already audibly fired for on
        //     the current closest tid.
        //
        //   Filtered overtake re-ack: when a track flips isBehind and
        //     others remain close, fire only if the remaining
        //     closest-urgency is strictly greater than the peak
        //     urgency the just-overtaking track ever reached.
        //
        //   Per-track tier latch: once we have audibly fired at
        //     urgency N for tid T, no re-fire for the same tid at the
        //     same tier. Re-fire requires a true tier raise N->N+1, a
        //     Clear (which resets all latches), or the born-close
        //     gate admitting a track it had been silencing (above),
        //     which drops that tid's latch. A full-tier de-escalation
        //     does NOT rearm it.
        // Latch-aware: a new entry whose closest tid has already been
        // audibly fired at this tier (its latch survived a deferred or
        // cancelled clear-grace) must not re-fire. Genuinely new tids have
        // latch 0, so urgency >= 1 always passes - this only suppresses a
        // car that flapped out and back within the clear-grace.
        val newEntryRaisesTier =
            newEntries.isNotEmpty() &&
                closestUrgency > prevClosestUrgency &&
                (
                    closestVehicle == null ||
                        closestUrgency > (firedTierPerTid[closestVehicle.id] ?: 0)
                    )
        val overtakeToHigher = if (overtakes.isNotEmpty() && stableTids.isNotEmpty()) {
            val peakOvertaken = overtakes.maxOf { peakUrgencyPerTid[it] ?: 0 }
            closestUrgency > peakOvertaken
        } else {
            false
        }
        // Escalation only counts if it's a true tier raise on the closest
        // tid that we haven't already fired for at that tier.
        val escalation = closestVehicle != null &&
            closestUrgency > prevClosestUrgency &&
            closestUrgency > (firedTierPerTid[closestVehicle.id] ?: 0)
        // Imminent-impact safety override. While the rider is at or
        // below the stationary speed threshold - or, with the low-speed
        // extension on, still moving at <= URGENT_MOVING_MAX_KMH - and
        // ANY vehicle in the close set looks imminent, fire
        // UrgentApproach, repeating at the episode pacing below.
        //
        // Two disjunct gates (a vehicle satisfying either fires):
        //   1. Proximity gate (radar-quantum strict): near-third
        //      distance AND closing faster than SAFETY_OVERRIDE_CLOSING_MS
        //      (URGENT_MOVING_CLOSING_FLOOR_MS is the binding floor on
        //      the moving path).
        //   2. TTC gate: time-to-collision below TTC_GATE_SECONDS,
        //      with a closing-speed floor (TTC_GATE_CLOSING_FLOOR_MS)
        //      that filters slow-queue traffic merging into the rider,
        //      and a distance ceiling at alertMaxM so we never reach
        //      out beyond what the alert envelope is configured for.
        //      Strictly extends the proximity gate's coverage at the
        //      same closing-speed bar: at 6 m/s closing, TTC <= 3 s
        //      maps to distance <= 18 m, while the proximity gate
        //      caught only the distance <= alertMaxM/3 = 6 m subset.
        //
        // Bypasses the stationary-suppress dwell. The dwell exists to
        // skip rolling stops mid-turn; it is a 2 s timer used as a
        // proxy for "rider has committed to a stop". When an imminent
        // threat is present the dwell is the wrong gate: TTC is sub-
        // 3 s, and waiting it out leaves the urgent tone silent
        // during the entire reaction window. A rider decelerating into
        // a junction with a closing vehicle is covered by the moving
        // path below, which has NO dwell at all: the threat predicate
        // itself (raised closing floor + proximity/TTC) carries the
        // discrimination, and a dwell would eat most of the sub-3 s
        // reaction window the cue exists to protect.
        //
        // No per-tid latch. Industry standards (TCAS, automotive FCW,
        // IEC 60601-1-8 medical, NFPA 72 smoke T3, ISO 7731 industrial)
        // all repeat-while-held for imminent-danger cues. DO NOT add a
        // per-tid latch: threat identity is not what re-arms the cue.
        // The repeat CADENCE within an episode is paced instead (see the
        // episode block below) - repeats continue while the condition
        // holds, at a rate meaningful for a rider rather than per beep
        // cooldown, and a genuinely-faster new closer overrides the
        // pacing immediately. Known interaction: because UrgentApproach
        // never writes firedTierPerTid, a car that de-escalates a full
        // tier after an urgent volley and then re-approaches can re-Beep
        // where the stationary-only behaviour stayed latched. Corpus
        // replay measured 11 such beeps across 105 rides - accepted,
        // since the re-approach is genuinely new threat information
        // after an imminent episode.
        // `stableClose` preserves the upstream order from
        // `RadarV2Decoder.snapshot()`, which sorts by `distanceM`
        // ascending. So `firstOrNull` here returns the CLOSEST
        // imminent-impact threat - the right one to pan the urgent
        // cue toward.
        val urgentViaMoving = urgentLowSpeedEnabled &&
            !riderBelowStationaryForUrgent &&
            bikeSpeedMs != null &&
            bikeSpeedMs * 3.6f <= URGENT_MOVING_MAX_KMH
        // Moving fires demand a stricter closing floor than the
        // stationary 6 m/s; a stationary rider keeps the shipped floors.
        val urgentClosingFloor =
            if (urgentViaMoving) URGENT_MOVING_CLOSING_FLOOR_MS else TTC_GATE_CLOSING_FLOOR_MS
        // Feed the predicted-pass fit with this frame's sightings BEFORE
        // evaluating the trigger, so the veto judges each candidate on the
        // freshest data (including the frame that would fire).
        updateLateralHistory(vehicles, nowMs)
        val imminentImpactTrigger = if (!riderBelowStationaryForUrgent && !urgentViaMoving) {
            null
        } else {
            stableClose.firstOrNull { v ->
                // Closing speed in m/s, positive = approaching.
                val closingMs = -v.speedMs
                val byProximity = closingMs >= urgentClosingFloor &&
                    v.speedMs <= SAFETY_OVERRIDE_CLOSING_MS &&
                    v.distanceM <= alertMaxM / 3
                val byTtc = closingMs >= urgentClosingFloor &&
                    closingMs >= TTC_GATE_CLOSING_FLOOR_MS &&
                    v.distanceM in 0..alertMaxM &&
                    v.distanceM.toFloat() / closingMs <= TTC_GATE_SECONDS
                (byProximity || byTtc) && urgentLaterallyPlausible(v, passClearanceM)
            }
        }
        // Urgent episode pacing. A qualifying-target gap longer than
        // URGENT_EPISODE_GAP_MS lapses the episode; the next fire is a
        // fresh first warning. Within an episode, fires are paced at
        // URGENT_EPISODE_REPEAT_MS - except a candidate closing at least
        // URGENT_NEW_THREAT_CLOSING_DELTA_MS faster than the episode's
        // peak, which is new severity and fires immediately. The peak
        // ratchets on EVERY qualifying check, audible or paced-out, so a
        // silent creep of ever-slightly-faster cars cannot re-trigger -
        // only a step change can. Peak/seen updates happen AFTER the
        // allow decision so a candidate is always judged against the
        // episode state before it arrived.
        if (imminentImpactTrigger != null &&
            urgentLastQualifyingSeenMs != NOT_INITIALIZED &&
            nowMs - urgentLastQualifyingSeenMs > URGENT_EPISODE_GAP_MS
        ) {
            urgentEpisodePeakClosing = 0f
            urgentFiredThisEpisode = false
        }
        val triggerClosingMs = imminentImpactTrigger?.let { -it.speedMs } ?: 0f
        val urgentAllowedByEpisode = imminentImpactTrigger != null &&
            (
                !urgentFiredThisEpisode ||
                    (urgentLastFireMs != NOT_INITIALIZED && nowMs - urgentLastFireMs >= URGENT_EPISODE_REPEAT_MS) ||
                    triggerClosingMs >= urgentEpisodePeakClosing + URGENT_NEW_THREAT_CLOSING_DELTA_MS
                )
        if (imminentImpactTrigger != null) {
            urgentLastQualifyingSeenMs = nowMs
            if (triggerClosingMs > urgentEpisodePeakClosing) {
                urgentEpisodePeakClosing = triggerClosingMs
            }
        }
        val anyImminentImpact = urgentAllowedByEpisode
        val triggered = newEntryRaisesTier || overtakeToHigher || escalation || anyImminentImpact
        if (triggered) beepPending = true

        // Scale the regular-Beep cooldown by rider speed. Do NOT
        // scale the UrgentApproach cadence; a slow / stationary rider in
        // front of an imminent threat gets the repeated warning at the
        // episode pacing, never widened by the slow-speed band.
        val sinceLastBeep = nowMs - lastBeepAtMs
        // E1: a strict tier raise (escalation) on the closest tid is new
        // threat information, not same-tier chatter, so per [escalationBypass]
        // it can bypass the inter-beep cooldown and fire the same frame
        // instead of being deferred (and dropped if the car overtakes before
        // the cooldown expires). The per-tid latch still blocks same-tier
        // re-fires, so this only adds the escalation beep the cooldown would
        // have dropped - it never silences anything.
        val escalationBypassesCooldown = when (escalationBypass) {
            EscalationCooldownBypass.NONE -> false
            EscalationCooldownBypass.ALL -> escalation
            EscalationCooldownBypass.TOP_TIER -> escalation && closestUrgency >= 3
        }
        val cooldownDone = when {
            // The urgent cue is the action channel; an awareness beep must not
            // be able to mute it. Its own episode pacing
            // ([URGENT_EPISODE_REPEAT_MS] plus the new-severity override) is
            // what limits repeats.
            anyImminentImpact ->
                urgentCooldown == UrgentCooldown.EPISODE_ONLY ||
                    sinceLastBeep >= minBeepGapMs
            escalationBypassesCooldown -> true
            else -> sinceLastBeep >= effectiveMinBeepGapMs(bikeSpeedMs)
        }

        // Clear-grace state machine. The all-clear is gated on RAW physical
        // presence (behindPresent), not the beep-path close set: while any car
        // is physically behind in range - even one docked as
        // isAlongsideStationary, or returning under a new tid - the Clear is
        // force-cancelled, so a still-present car can never fire "road clear".
        // The Clear (and the per-track latch wipe) is deferred until behind has
        // stayed empty for clearGraceMs. closeEpisodeActive is the arming latch:
        // once a stable-close track existed this episode, the Clear still arms
        // when the car finally leaves, even though the docked window kept
        // stableClose (and prevStableClose) empty for many frames. The
        // surviving firedTierPerTid latch suppresses any same-car re-beep
        // through the latch-aware new-entry / escalation gates.
        // Turn-aware clear deferral: an empty behind-set mid-corner is a
        // radar blackout, not an empty road - cornering sweeps the rear cone
        // off every followed car. While the rider is TURNING with a live
        // episode, keep cancelling the pending Clear; the deferral thereby
        // spans the rider's whole transit of the corner, however sharp or
        // long. On the first HOLD frame after straightening with the road
        // still reading empty, anchor an adaptive tail: the follower needs
        // lastBehindDistanceM / riderSpeed more seconds to traverse the same
        // corner (it runs the rider's path offset by its following
        // distance). A car that fails to reappear within the tail has
        // genuinely turned off, and the deferred Clear fires after the
        // normal grace. Any behind-present frame cancels the tail - the
        // follower is back, normal clear semantics resume. Delayed all-clear
        // over false all-clear, same asymmetry as the grace itself.
        if (stableTids.isNotEmpty()) closeEpisodeActive = true
        if (behindPresent) {
            lastBehindDistanceM = behindPresentVehicles.minOf { it.distanceM }
            turnDeferUntilMs = Long.MIN_VALUE
        }
        val turningBlackout = turnState == TurnStateDecider.State.TURNING && closeEpisodeActive
        if (turningBlackout) {
            turnDeferUntilMs = Long.MIN_VALUE
        } else if (turnState == TurnStateDecider.State.HOLD &&
            closeEpisodeActive &&
            !behindPresent &&
            turnDeferUntilMs == Long.MIN_VALUE
        ) {
            // Anchor once per turn. The speed floor keeps the tail finite
            // for a stopped or crawling rider; the clamps bound it for
            // degenerate distance/speed readings.
            val tailMs =
                (lastBehindDistanceM / max(bikeSpeedMs ?: 0f, TURN_TAIL_MIN_SPEED_MS) * 1000)
                    .toLong()
                    .coerceIn(TURN_TAIL_MIN_MS, TURN_TAIL_MAX_MS)
            turnDeferUntilMs = nowMs + tailMs
            onTurnDefer(tailMs)
        }
        // The tail outlives the HOLD state on purpose: a short HOLD must not
        // cut the deferral off before the follower's catch-up time elapses.
        if (behindPresent || turningBlackout || nowMs < turnDeferUntilMs) {
            clearPending = false
        } else if (closeEpisodeActive && !clearPending) {
            clearPending = true
            clearPendingSinceMs = nowMs
        }
        val clearGraceElapsed = clearPending &&
            !behindPresent &&
            (nowMs - clearPendingSinceMs) >= clearGraceMs

        val event: Event = when {
            clearGraceElapsed -> {
                lastBeepAtMs = nowMs
                beepPending = false
                firedTierPerTid.clear()
                peakUrgencyPerTid.clear()
                bornCloseGate.onClear()
                turnDeferUntilMs = Long.MIN_VALUE
                clearPending = false
                closeEpisodeActive = false
                Event.Clear
            }
            beepPending && cooldownDone && stableTids.isNotEmpty() -> {
                when {
                    anyImminentImpact -> {
                        // Held imminent threat: re-fire at the episode
                        // pacing until it clears. Carry the triggering
                        // vehicle's lateralPos so audio consumers can pan
                        // to the threat's side when the experimental
                        // directional-audio flag is on.
                        lastBeepAtMs = nowMs
                        beepPending = false
                        urgentLastFireMs = nowMs
                        urgentFiredThisEpisode = true
                        Event.UrgentApproach(
                            lateralPos = imminentImpactTrigger.lateralPos,
                            viaMovingPath = urgentViaMoving,
                            triggerTid = imminentImpactTrigger.id,
                            triggerDistanceM = imminentImpactTrigger.distanceM,
                            triggerClosingMs = -imminentImpactTrigger.speedMs,
                            triggerRangeXm = imminentImpactTrigger.rangeXm,
                        )
                    }
                    riderStationary -> {
                        // Stationary, no imminent threat — suppress
                        // ordinary beeps. Don't consume cooldown or
                        // beepPending: when the rider rolls off, the
                        // next decide() call can fire same-frame.
                        Event.None
                    }
                    else -> {
                        val v = closestVehicle
                        // Ghost-beep filter vetoes. Consume the pending
                        // beep WITHOUT touching lastBeepAtMs or the
                        // per-tid latch: no audio happened, so the
                        // cooldown must not advance and a later
                        // admission re-fire must see a clean latch.
                        val gateVeto = v != null &&
                            bornCloseGate.isGated(v)
                        val rxVeto = v != null &&
                            !v.lateralUnknown &&
                            abs(v.rangeXmRaw) > RX_ABSURD_M
                        when {
                            gateVeto -> {
                                beepPending = false
                                bornCloseGate.noteSuppressed(v)
                                onGateEvent(
                                    "# gate suppress tid=${v.id} tier=$closestUrgency" +
                                        " d=${v.distanceM} eff=${alertDistanceM(v)}" +
                                        " closing=${-v.speedMs}" +
                                        " birth_d=${v.bornDistanceM} turn=$turnState",
                                )
                                Event.None
                            }
                            rxVeto -> {
                                beepPending = false
                                onGateEvent(
                                    "# gate rx-veto tid=${v.id} tier=$closestUrgency" +
                                        " d=${v.distanceM} eff=${alertDistanceM(v)}" +
                                        " raw_rx=${v.rangeXmRaw}",
                                )
                                Event.None
                            }
                            else -> {
                                lastBeepAtMs = nowMs
                                beepPending = false
                                // closestVehicle's lateralPos feeds
                                // directional audio when the experimental
                                // flag is on; defaults to 0f when no
                                // closest is tracked (defensive -
                                // beepPending shouldn't normally reach
                                // here in that state).
                                if (v != null) {
                                    firedTierPerTid[v.id] = closestUrgency
                                    Event.Beep(count = closestUrgency, lateralPos = v.lateralPos)
                                } else {
                                    Event.Beep(count = closestUrgency, lateralPos = 0f)
                                }
                            }
                        }
                    }
                }
            }
            else -> Event.None
        }

        prevStableClose = stableTids
        prevClosestUrgency = if (stableTids.isEmpty()) 0 else closestUrgency
        prevCloseRaw = currentCloseTids
        prevBehindRaw = behindPresentTids
        return event
    }

    fun reset() {
        bornCloseGate.reset()
        consecutiveClose.clear()
        framesSinceLastSeen.clear()
        firedTierPerTid.clear()
        peakUrgencyPerTid.clear()
        lastBehindDistanceM = 0
        turnDeferUntilMs = Long.MIN_VALUE
        prevStableClose = emptySet()
        prevClosestUrgency = 0
        lastBeepAtMs = Long.MIN_VALUE / 2
        beepPending = false
        clearPending = false
        clearPendingSinceMs = 0L
        closeEpisodeActive = false
        prevCloseRaw = emptySet()
        prevBehindRaw = emptySet()
        lastNotStationaryAtMs = NOT_INITIALIZED
        lateralHistory.clear()
        passGateLogged.clear()
        passGateOkLogged.clear()
        urgentLastQualifyingSeenMs = NOT_INITIALIZED
        urgentLastFireMs = NOT_INITIALIZED
        urgentEpisodePeakClosing = 0f
        urgentFiredThisEpisode = false
    }

    /** Append this frame's usable lateral sightings to the per-track fit
     *  history and drop stale runs. `isBehind` tracks are useless for the
     *  pass prediction (already past the rider); `lateralUnknown` frames
     *  carry a held-over lateral value, not a measurement, and would bias
     *  the fit toward a stale offset.
     *
     *  A sighting identical to the deque tail refreshes the tail's
     *  timestamp instead of appending. The decoder snapshot repeats a
     *  track's HELD values on every notify that didn't re-measure it, so
     *  in traffic each real measurement arrives followed by duplicates;
     *  appending them evicted the older, spread-out samples from the
     *  window and collapsed the fit's distance span below its confidence
     *  floor - the predicted-pass veto then failed open on exactly the
     *  adjacent-lane passes it exists to reject (ride evidence: a rider
     *  stopped at a light, every urgent cue of the ride an adjacent-lane
     *  pass, none of them with a usable fit). A
     *  duplicated measurement carries no new fit information, so
     *  collapsing genuine repeats too costs nothing; refreshing the
     *  timestamp keeps the gap-reset semantics for hovering tracks. */
    private fun updateLateralHistory(vehicles: List<Vehicle>, nowMs: Long) {
        for (v in vehicles) {
            if (v.isBehind || v.lateralUnknown) continue
            val h = lateralHistory.getOrPut(v.id) { ArrayDeque() }
            val last = h.lastOrNull()
            if (last != null && nowMs - last.atMs > URGENT_PASS_HISTORY_RESET_MS) {
                // Same tid after a dead gap = a recycled track id. A fit
                // seeded with the previous car's geometry would be lying.
                h.clear()
                // Drop the logged-verdict memo HERE, not in the stale sweep
                // below: this frame re-stamps the deque to nowMs, so by the
                // time the sweep runs the track no longer looks stale and
                // the new car would inherit the old one's verdict - and its
                // own first decision would be deduped into silence.
                passGateLogged.remove(v.id)
                passGateOkLogged.remove(v.id)
            }
            val tail = h.lastOrNull()
            if (tail != null && tail.distanceM == v.distanceM && tail.rangeXm == v.rangeXm) {
                h.removeLast()
            }
            h.addLast(LateralSample(nowMs, v.distanceM, v.rangeXm))
            while (h.size > URGENT_PASS_HISTORY_MAX) h.removeFirst()
        }
        // Drop whole tracks whose newest sample has gone stale, so a
        // recycled tid that reappears only as isBehind/lateralUnknown can
        // never be judged on another car's history.
        lateralHistory.entries.removeAll { (tid, deque) ->
            val newest = deque.lastOrNull()
            val stale = newest == null || nowMs - newest.atMs > URGENT_PASS_HISTORY_RESET_MS
            // The logged-verdict memo shadows the history: a recycled tid
            // must not inherit the previous car's line and so go unlogged.
            if (stale) {
                passGateLogged.remove(tid)
                passGateOkLogged.remove(tid)
            }
            stale
        }
    }

    /** Lateral-plausibility vetoes for an urgent candidate; true = the
     *  candidate may fire. Fails OPEN where no lateral truth exists: no
     *  lateral data (`rangeXm == 0f` from lateral-free sources decodes as
     *  dead centre and passes) and unconfident fits fire; an
     *  unknown-sentinel firing frame stands down only the instantaneous
     *  off-axis veto, never the history-derived pass prediction. */
    private fun urgentLaterallyPlausible(v: Vehicle, passClearanceM: Float): Boolean {
        // Unknown-sentinel frames hold a carried-forward lateral value, not
        // a measurement, so the OFF-AXIS veto (an instantaneous read of the
        // firing frame) stands down. The PREDICTED-PASS veto does NOT: its
        // fit is built exclusively from the track's prior MEASURED frames
        // ([updateLateralHistory] skips unknown frames), so it needs nothing
        // from the firing frame. Standing it down too was a field false
        // positive: the radar drops lateral measurement exactly when a car
        // rides the cone edge - far off-axis - so "lateral unknown" arrives
        // correlated with the very geometry the veto exists to reject, and a
        // wide parallel-lane pass (12 measured samples predicting ~5.5 m)
        // fired the urgent cue on the one frame whose lateral went unknown.
        if (!v.lateralUnknown) {
            // Off-axis veto: two-plus lanes to the side on the firing frame
            // is crossing/parallel traffic, not an impact line.
            if (abs(v.rangeXm) > URGENT_LATERAL_MAX_M) {
                logPassGate(v.id, "offaxis") {
                    "# gate urgent-offaxis tid=${v.id} d=${v.distanceM} rx=${v.rangeXm}"
                }
                return false
            }
        }
        // Predicted-pass veto: only with a confident fit.
        return when (val fit = predictedPassFit(v.id)) {
            is PassFit.Unconfident -> true
            is PassFit.FreshOverride -> {
                // The fail-open path: a stale fit wanted to judge this
                // candidate and the freshest measurement refused to back
                // it. Logged because it is the branch that lets an urgent
                // SOUND where the wide fit alone would have silenced it.
                logPassGate(v.id, "open") {
                    "# gate urgent-pass-open tid=${v.id} newest_rx=${fit.newestRangeXm} d=${v.distanceM}"
                }
                true
            }
            is PassFit.Confident -> {
                val (minClearance, threshold) = when (passScoring) {
                    PassScoring.BIKE_ENVELOPE -> fit.minClearanceM to passClearanceM
                    PassScoring.RADAR_POINT -> abs(fit.predictedRangeXm) to URGENT_PASS_LATERAL_MIN_M
                }
                // A predicted hit scores zero and is never vetoed, however
                // low the rider sets the margin. Without the second term a
                // margin of zero would veto exactly the case the cue exists
                // for ("a predicted hit is never vetoed, even at a zero
                // margin" pins it).
                val veto = minClearance >= threshold && minClearance > 0f
                // Both verdicts are logged, not just the veto: otherwise a
                // fired cue records the threshold with no value beside it,
                // and a confident pass reads the same as no fit at all.
                // min_clearance is a distance and carries no side, so the
                // signed intercept rides along - a mount offset shows up as
                // decisions stacking on one side, which needs a sign to see.
                val tag = if (veto) "veto" else "ok"
                val message = {
                    "# gate urgent-pass-$tag tid=${v.id} fit=${fit.source}" +
                        " min_clearance=$minClearance gate_clearance_m=$threshold" +
                        " intercept=${fit.predictedRangeXm} d=${v.distanceM}"
                }
                if (veto) {
                    logPassGate(v.id, "veto:${fit.source}", message)
                } else if (passGateOkLogged.getOrPut(v.id) { HashSet() }.add(fit.source)) {
                    onGateEvent(message())
                }
                !veto
            }
        }
    }

    /** Emit a pass-gate decision to the capture log once per verdict per
     *  track. This gate is evaluated inside the urgent-candidate search on
     *  every frame, so a car lingering as a candidate beside the rider is
     *  judged many times a second; only a CHANGE in what the gate DECIDED
     *  is news.
     *
     *  [verdict] is the dedupe key and must carry no per-frame numbers.
     *  Keying on the formatted line instead would defeat the whole purpose:
     *  distance ticks down and the fitted intercept jitters every frame, so
     *  a dwelling car would emit a fresh "identical" verdict at frame rate.
     *  The numbers still reach the log - they ride in [message], recorded on
     *  the transition that is worth reading later.
     *
     *  This memo covers the off-axis, corroboration and veto verdicts only.
     *  The confident-PASS verdict keeps its own ([passGateOkLogged]) and
     *  never reaches here; a fifth verdict added to this one would re-arm
     *  all three of the above, which is the trap that split them.
     *
     *  A consequence worth knowing before reading a capture: these lines are
     *  each verdict's FIRST occurrence for a track, not a chronology. On
     *  veto -> pass -> veto the second veto is deduped, so the last gate line
     *  for that track says `ok` while the verdict in force was `veto`. Read
     *  the set of lines, not the last one. */
    private fun logPassGate(tid: Int, verdict: String, message: () -> String) {
        if (passGateLogged[tid] == verdict) return
        passGateLogged[tid] = verdict
        onGateEvent(message())
    }

    /** Outcome of the pass prediction for one track: either a trusted
     *  intercept (with the window that produced it, for the capture log),
     *  no usable fit at all, or a stale fit the freshest measurement
     *  refused to corroborate. The last two both fail OPEN; they are
     *  distinct only so the capture log can tell them apart after a ride. */
    private sealed interface PassFit {
        /** [predictedRangeXm] is the fitted offset where the track draws
         *  level with the RADAR; [minClearanceM] is the closest the same
         *  fitted line comes to the bike's centreline anywhere along the
         *  bike. Both are carried so [PassScoring] can pick without
         *  refitting. */
        data class Confident(
            val predictedRangeXm: Float,
            val minClearanceM: Float,
            val source: String,
        ) : PassFit
        data object Unconfident : PassFit
        data class FreshOverride(val newestRangeXm: Float) : PassFit
    }

    /** Least-squares extrapolation of the track's lateral offset at
     *  distance 0 (the pass point). Fits the newest
     *  [URGENT_PASS_RECENT_FIT_WINDOW] samples first - the short window
     *  reacts to a genuine swerve toward the rider within a couple of
     *  frames. When that window is unconfident (thin, or covering too
     *  narrow a distance band), falls back to the full retained history:
     *  near the pass point the recent samples bunch at nearly one
     *  distance (a stopped rider watching a car walk the last metres in,
     *  or a track that hovered before committing), and judging only that
     *  band left the veto blind exactly where the stationary urgent
     *  gates fire (ride evidence: the false urgents all fired inside
     *  10 m with a span-starved recent window, while the full approach
     *  showed a committed 2.5-3.5 m side pass).
     *
     *  The fallback may only veto where the newest MEASURED sample
     *  corroborates it. That window necessarily holds stale approach
     *  geometry, and a least-squares fit lets dozens of old wide-offset
     *  points outvote the handful showing a car swinging INTO the rider's
     *  line. A car whose latest measured offset is already inside
     *  [URGENT_PASS_LATERAL_MIN_M] is not committed to a side pass -
     *  whatever lane it held on the approach - so the stale fit does not
     *  get to veto it.
     *
     *  Everything here fails OPEN: an unconfident fit, a refused
     *  corroboration, and a track never measured across enough approach
     *  all let the cue fire. First warnings are never delayed for lack of
     *  history. */
    private fun predictedPassFit(tid: Int): PassFit {
        val all = lateralHistory[tid]?.toList().orEmpty()
        val recent = all.takeLast(URGENT_PASS_RECENT_FIT_WINDOW)
        fitLine(recent)?.let { return confident(it, "recent") }
        val newest = all.lastOrNull() ?: return PassFit.Unconfident
        // Deliberately NOT the configured clearance. The veto asks "is this
        // predicted to clear me?", which is the rider's margin; this asks "is
        // the freshest measurement close enough that a STALE fit must not be
        // trusted?", which is a question about the fit and not about comfort.
        // Tying them together narrows this fail-open every time a rider
        // tightens the margin, and it is the guard that fires the cue for a
        // car crossing INTO the rider's line (AlertDeciderTest, "stale
        // side-pass history cannot veto a car swinging into the rider").
        if (abs(newest.rangeXm) < URGENT_PASS_LATERAL_MIN_M) {
            return PassFit.FreshOverride(newest.rangeXm)
        }
        val fallback = fitLine(all) ?: return PassFit.Unconfident
        return confident(fallback, "fallback")
    }

    /** Package a fitted line as a [PassFit.Confident], carrying both the
     *  radar-point offset and the whole-bike minimum so [PassScoring] can
     *  choose without refitting. The line is straight, so its closest
     *  approach to the centreline over a span is at one of the two ends
     *  unless it crosses the centreline in between - which is a predicted
     *  hit, clearance zero. */
    private fun confident(line: Pair<Float, Float>, source: String): PassFit.Confident {
        val (a, b) = line
        val front = a + b * -BIKE_AHEAD_OF_RADAR_M
        val rear = a + b * BIKE_BEHIND_RADAR_M
        val clearance = if ((front > 0f) != (rear > 0f)) 0f else minOf(abs(front), abs(rear))
        return PassFit.Confident(a, clearance, source)
    }

    /** Least-squares fit of rangeXm = a + b * distanceM over [samples],
     *  returning intercept and slope. Null when the samples are too thin to
     *  trust - fewer than [URGENT_PASS_FIT_MIN_POINTS] points or an approach
     *  span shorter than [URGENT_PASS_FIT_MIN_SPAN_M] (a line extrapolated
     *  from a narrow distance band amplifies radar jitter). */
    private fun fitLine(samples: List<LateralSample>): Pair<Float, Float>? {
        if (samples.size < URGENT_PASS_FIT_MIN_POINTS) return null
        val minD = samples.minOf { it.distanceM }
        val maxD = samples.maxOf { it.distanceM }
        if (maxD - minD < URGENT_PASS_FIT_MIN_SPAN_M) return null
        val n = samples.size.toDouble()
        val sumD = samples.sumOf { it.distanceM.toDouble() }
        val sumX = samples.sumOf { it.rangeXm.toDouble() }
        val sumDd = samples.sumOf { it.distanceM.toDouble() * it.distanceM }
        val sumDx = samples.sumOf { it.distanceM.toDouble() * it.rangeXm }
        val denom = n * sumDd - sumD * sumD
        if (denom <= 0.0) return null
        val b = (n * sumDx - sumD * sumX) / denom
        return ((sumX - b * sumD) / n).toFloat() to b.toFloat()
    }

    /**
     * Scale the inter-beep cooldown by current rider speed. Slow
     * traffic / lights gets a wider cooldown; fast descents get a
     * tighter one. Returns the base [minBeepGapMs] when no speed signal
     * is available (the no-eBike, no-radar-speed fallback path).
     *
     * Since E1 ([escalationBypass] = [EscalationCooldownBypass.ALL] by
     * default), a strict tier raise bypasses the cooldown entirely, so
     * this scaling no longer gates tier raises - it governs only the
     * residual non-escalation pending-beep paths (e.g. a beep deferred
     * across a stationary-suppress window, then released on roll-off).
     * The band split is exercised directly by `effectiveMinBeepGapMs`
     * unit tests and end-to-end under [EscalationCooldownBypass.NONE].
     *
     * When eBike is bonded the caller supplies [bikeSpeedMs] from the
     * bike's wheel-speed sensor (sub-second ground truth). When eBike is
     * absent the caller supplies the radar's bike-speed field (from the
     * V2 device-status frame); the decider doesn't care about the
     * source, only the magnitude.
     */
    internal fun effectiveMinBeepGapMs(bikeSpeedMs: Float?): Long {
        if (bikeSpeedMs == null) return minBeepGapMs
        val kmh = bikeSpeedMs * 3.6f
        return when {
            kmh < SPEED_AWARE_COOLDOWN_SLOW_KMH -> minBeepGapMs * 2
            kmh > SPEED_AWARE_COOLDOWN_FAST_KMH -> minBeepGapMs / 2
            else -> minBeepGapMs
        }
    }

    /**
     * Distance the awareness tiers are scored on: the target's TRUE range,
     * not its along-axis [Vehicle.distanceM].
     *
     * `distanceM` is the radar's rangeY - how far back a target is along the
     * bike's axis, with its lateral offset thrown away. That collapses as a
     * vehicle draws abeam, so a car leaving the road to the side climbs the
     * tiers exactly like one closing in. Observed on the road: a car turning
     * off at a junction held ~8.7 m of lateral offset while its rangeY fell
     * 13.9 -> 7.4 m, earning the top-tier cue, though its true range never
     * came inside 10.8 m. Tiering on the hypotenuse makes departure sound
     * like departure.
     *
     * One-sided by construction: `hypot >= rangeY` on every frame, so a tier
     * scored on true range crosses its boundary on the same frame as the
     * rangeY scoring or a later one, never an earlier one. For a
     * genuinely-behind car the two agree closely (a dead-behind car with 2 m
     * of lateral offset crosses the tier-3 boundary at 9.80 m of rangeY
     * instead of 10.0). For an off-axis car the escalation arrives later or
     * not at all: a departing car whose true range never comes inside a
     * boundary keeps its lower tier, which is the intended behaviour, not a
     * cost - `a car drawing abeam does not reach the top tier` pins it, and
     * the missing top-tier cue there is not a regression to be fixed.
     *
     * Scope is the tier score and which vehicle the audio describes, nothing
     * else. Close-set entry, the all-clear presence gate and the
     * urgent-impact gates do not call this helper and keep reading
     * `distanceM`, deliberately: rangeY is the smaller of the two, so each of
     * those keeps the broader trigger - a track is admitted, a "road clear"
     * is withheld, and the act-now cue is armed on the along-axis distance.
     *
     * Uses the mount-offset-corrected [Vehicle.rangeXm], since the question
     * is the range to the RIDER. Fails open on [Vehicle.lateralUnknown]: with
     * no lateral measurement this returns `distanceM` unchanged, i.e. exactly
     * today's behaviour, so an unmeasured frame can never hold a cue back.
     * The decoder carries a stale lateral value across the whole sentinel run
     * and honouring it would delay a real car's escalation on data the
     * decoder itself flags as not a measurement.
     */
    private fun alertDistanceM(v: Vehicle): Float = if (tierDistance == TierDistance.ALONG_AXIS || v.lateralUnknown) {
        v.distanceM.toFloat()
    } else {
        hypot(v.distanceM.toFloat(), v.rangeXm)
    }

    private fun urgencyFor(distM: Float, alertMaxM: Int): Int {
        val third = alertMaxM / 3f
        return when {
            distM <= third -> 3
            distM <= 2f * third -> 2
            else -> 1
        }
    }

    companion object {
        /** Ghost-beep filter: a tier-beep trigger with RAW lateral offset
         *  beyond this is physically not on the rider's road (a full
         *  carriageway is ~7 m) - vehicles on parallel streets have fired
         *  tier beeps at raw rx 13-22 m in ride captures. Raw (the
         *  sensor's own reading) because a physical-plausibility veto
         *  must not depend on rider configuration: the mount-offset
         *  translation is centimetres against a 10 m bar. An order
         *  looser than any lane-discrimination gate on purpose: this only
         *  rejects the physically impossible, never judges lane position. */
        const val RX_ABSURD_M = 10f

        /** Sentinel for [lastNotStationaryAtMs] meaning "no `decide()`
         *  call has yet been processed this session". The first call
         *  replaces it with `nowMs` so dwell starts counting from there.
         *  Cannot reuse the `Long.MIN_VALUE / 2` idiom that [lastBeepAtMs]
         *  uses: `nowMs - Long.MIN_VALUE / 2` overflows positive on the
         *  first call and would satisfy `>= stationaryDwellMs`
         *  immediately, silencing the first beep. */
        private const val NOT_INITIALIZED: Long = Long.MIN_VALUE

        /** Divisor for the close-set exit-hysteresis distance band: a track
         *  already in the close set stays until `distanceM` exceeds
         *  `alertMaxM + alertMaxM/`this. 10 gives a 10% band (3 m at the
         *  common alertMaxM=30), comfortably wider than the radar's ~1 m
         *  edge jitter without reaching for cars meaningfully past the
         *  rider's configured alert envelope. For alertMaxM < 10 the integer
         *  band is 0 (exit reverts to the hard alertMaxM); the clear-grace
         *  still absorbs single-frame flaps in that degenerate range. */
        private const val CLOSE_EXIT_HYSTERESIS_DIVISOR: Int = 10

        /** Consecutive frames a track must be ABSENT from the close set before
         *  its sustain counter is reset. 2 = a single missed frame (one dropped
         *  BLE notification) is tolerated; the second consecutive miss resets.
         *  See [framesSinceLastSeen]. [W8] */
        private const val GRACE_MISS_FRAMES: Int = 2

        /** Closing speed (m/s, signed; negative = approaching) at or
         *  below which a stationary rider's suppress gate is overridden,
         *  when paired with near-third proximity.
         *
         *  Decoded `speedMs` is Float at the radar's native 0.5 m/s
         *  quantum, so legal closing values are ..., -6.5, -6.0, -5.5,
         *  -5.0, ... A naive choice of -5f sits on the quantisation
         *  step: a target whose real closing speed sits near 5 m/s
         *  would flap across the threshold from frame to frame as the
         *  raw byte oscillates between -10 and -11. -6f is one quantum
         *  stricter and corresponds to a real closing speed of
         *  >= 6.0 m/s (~22 km/h), which matches "vehicle still going
         *  at urban cruising speed without braking for the queue"
         *  better than -5f (~18 km/h) does. */
        const val SAFETY_OVERRIDE_CLOSING_MS = -6f

        /** Mini-dwell for the imminent-impact override path. Much
         *  shorter than [stationaryDwellMs] (which exists to skip
         *  rolling stops mid-turn for the ordinary-Beep suppress
         *  path). 500 ms is long enough to absorb single-frame
         *  radar bike-speed noise and 200-400 ms mid-turn speed
         *  dips, short enough that a rider decelerating into a
         *  junction with a closing vehicle gets the urgent tone
         *  well within the time-to-collision window for the gate
         *  (closing ≥ 6 m/s AND distance ≤ alertMaxM/3). */
        const val URGENT_OVERRIDE_DWELL_MS = 500L

        /** Time-to-collision threshold (seconds) for the TTC disjunct
         *  of the imminent-impact safety override. Set toward the lower
         *  end of the automotive forward-collision-warning range
         *  (Mercedes Pre-Safe, Volvo RCW, NHTSA Burgett & Carter warn at
         *  2.8-4 s) - and that range assumes a driver who only has to
         *  brake. A stopped or crawling cyclist's evasive action
         *  (dismount, step aside, brace) is slower, so the rider needs at
         *  least that much lead, not less. Raising 2.0 -> 3.0 s was
         *  validated on a 105-ride corpus replay: it added urgent beeps
         *  only on genuinely-close encounters (got-close precision 86%
         *  vs 90% at 2.0 s; every new alarm on a previously-silent ride
         *  was a real 1-4 m pass), so the wider window does not
         *  degenerate into beep noise. The 6 m/s closing floor filters
         *  slow-queue traffic; the alertMaxM distance ceiling caps the
         *  reach, so a larger value is a near-no-op (6 m/s x ~3.3 s
         *  already hits the 20 m window). */
        const val TTC_GATE_SECONDS = 3.0f

        /** Minimum closing speed (m/s, positive = approaching) for the
         *  TTC disjunct to engage. Mirrors [SAFETY_OVERRIDE_CLOSING_MS]
         *  on the proximity disjunct so both gates of the override's
         *  stationary path share the same quantum-strict closing bound - the
         *  -5/-6 quantum boundary on the proximity gate exists to
         *  avoid radar-noise flap, and the same reasoning applies
         *  here. Anything below 6 m/s catches too much queueing
         *  traffic merging into a stopped rider, where the driver is
         *  clearly tracking and braking. */
        const val TTC_GATE_CLOSING_FLOOR_MS = 6f

        /** Rider speed (km/h) at or below which the imminent-impact
         *  override is also evaluated while MOVING (when the rider has
         *  not satisfied the stationary mini-dwell and the Settings
         *  toggle is on). The justification is relative-doppler
         *  semantics, not "escape options": decoded closing speed is
         *  measured relative to the rider, so the same closing floor
         *  means a much faster absolute vehicle when the rider is slow.
         *  At <= 15 km/h a [URGENT_MOVING_CLOSING_FLOOR_MS] closer is a
         *  ~50+ km/h vehicle bearing down on a slow or decelerating
         *  rider; at 25 km/h rider speed the same floor would catch
         *  ordinary overtakes. Deliberately its own constant - NOT a
         *  reuse of [SPEED_AWARE_COOLDOWN_SLOW_KMH] - so cooldown-band
         *  tuning can never silently move a safety gate. */
        const val URGENT_MOVING_MAX_KMH = 15f

        /** Closing-speed floor (m/s, positive = approaching) applied to
         *  BOTH urgent disjuncts when the gate opened via the moving
         *  path; the stationary path keeps the shipped 6 m/s floors.
         *  Corpus-tuned over 105 ride captures: at the
         *  stationary 6 m/s floor the moving extension fired roughly
         *  once per ride, dominated by routine 35-45 km/h overtakes of
         *  a slow rider; at 10 m/s it fires ~4-5 times per week, all on
         *  genuinely fast closers, while still catching the motivating
         *  decelerating-into-junction truck pass. Sits on the radar's
         *  0.5 m/s speed quantum like the stationary floors (raw -20 =
         *  -10.0 m/s fires; raw -19 = -9.5 m/s does not). */
        const val URGENT_MOVING_CLOSING_FLOOR_MS = 10f

        /** Bike speeds below this (km/h) double the [minBeepGapMs]
         *  cooldown. Slow urban crawl is where flapping beeps come from
         *  as the queue creeps forward; the wider gap damps the residual
         *  re-statements the cooldown still governs (tier raises bypass
         *  it since E1, so this no longer delays a genuine escalation). */
        const val SPEED_AWARE_COOLDOWN_SLOW_KMH = 15f

        /** Floor (m/s) on the rider speed used to size the post-turn
         *  clear-deferral tail. The tail is follower distance over rider
         *  speed - the time the car behind needs to traverse the corner
         *  the rider just did - and a stopped or crawling rider would
         *  otherwise divide by (near) zero and defer indefinitely.
         *  1.5 m/s (~5 km/h, walking pace) keeps the tail finite while
         *  still stretching it for a slow rider, whose follower
         *  genuinely takes longer to come round the corner. */
        const val TURN_TAIL_MIN_SPEED_MS = 1.5f

        /** Lower clamp (ms) on the adaptive post-turn tail. Even a
         *  close follower takes a couple of seconds to clear the corner
         *  and be reacquired by the rear cone; anything shorter would
         *  let a tight, fast corner fire the all-clear while the car is
         *  still mid-bend. */
        const val TURN_TAIL_MIN_MS = 3_000L

        /** Upper clamp (ms) on the adaptive post-turn tail. Matches
         *  [TurnStateDecider.HOLD_MS]: a follower that has not
         *  reappeared 10 s after the rider straightened has genuinely
         *  turned off, and holding the all-clear longer would erode
         *  trust in the cue on every real departure at a junction. */
        const val TURN_TAIL_MAX_MS = 10_000L

        /** Bike speeds above this (km/h) halve the [minBeepGapMs]
         *  cooldown. Reaction time at 30 km/h is roughly half what it is
         *  at 15 km/h, so the residual (non-escalation) cooldown re-arms
         *  proportionally faster; tier raises themselves bypass the
         *  cooldown entirely since E1. */
        const val SPEED_AWARE_COOLDOWN_FAST_KMH = 25f

        /** Maximum |rangeXm| (m) an urgent candidate may sit off-axis on
         *  the firing frame. 6 m is just under two UK lane widths
         *  (~3.65 m each): rear-cone traffic that can reach the rider
         *  within the <= 3 s TTC window cannot be two lanes to the side,
         *  and the margin absorbs any residual error from the
         *  mount-offset setting plus a genuine same-lane offset. Ride
         *  evidence: parallel-street artefacts fired from 7-33 m
         *  off-axis while the closest genuine urgent candidates all
         *  read within ~3 m. */
        const val URGENT_LATERAL_MAX_M = 6f

        /** Minimum |predicted pass rangeXm| (m) at which the
         *  predicted-pass veto suppresses an urgent candidate, under
         *  [PassScoring.RADAR_POINT] only: the fit says the car crosses
         *  distance 0 at least this far to the side. Deliberately loose - a
         *  dead-centre threat must never be vetoed, so the threshold must
         *  exceed the residual error of the mount-offset setting plus fit
         *  noise with margin. Retained for the corpus A/B;
         *  [DEFAULT_PASS_CLEARANCE_M] is what ships. */
        const val URGENT_PASS_LATERAL_MIN_M = 2.5f

        /** How far the bike reaches in front of and behind the radar. The
         *  radar sits on the seatpost, so the machine extends well past it
         *  forward and a little aft, and a converging vehicle reaches the
         *  front wheel first.
         *
         *  A conservative estimate for one bike, not a measurement and not a
         *  spec figure. Erring long is the safe direction: over-stating the
         *  reach lowers the scored clearance, so the error fires cues rather
         *  than silencing them. A radar mounted somewhere other than the
         *  seatpost - a rack, a saddlebag - has a different fore/aft split
         *  and this does not adapt to it; the lateral mount offset is
         *  configurable ([data.Prefs.radarLateralOffsetCm]) but the
         *  longitudinal span is not. */
        const val BIKE_AHEAD_OF_RADAR_M = 2.0f
        const val BIKE_BEHIND_RADAR_M = 0.5f

        /** Default clearance (m) from the bike's CENTRELINE that a predicted
         *  pass must keep, anywhere along the bike, before the imminent-impact
         *  cue is vetoed. Subtract the half-width at the bars to read it as
         *  clearance from the bodywork. Rider-configurable between
         *  [MIN_PASS_CLEARANCE_M] and [MAX_PASS_CLEARANCE_M].
         *
         *  **The hazard this number carries.** The veto acts on a fitted
         *  intercept, not a measurement, so intercept error - fit noise over a
         *  short approach span, plus whatever the mount-offset setting leaves
         *  behind - can score a vehicle wider than it really passes and veto a
         *  genuinely close one. That is why [URGENT_PASS_LATERAL_MIN_M], the
         *  threshold this replaces, was set loose enough to swallow the error
         *  rather than tuned. Lowering this value spends that safety margin.
         *
         *  **How 1.5 m answers it - measured, not reasoned.** An offline
         *  sweep over 183 ride captures at `alertMaxM` 30, above the 20 m
         *  default, removes 16 of 100 urgent cues at
         *  this value, and 14 vehicles lose their urgent cue outright rather
         *  than losing a repeat. Three of the removals are on vehicles that
         *  came within 2.0 m of the radar: two are the reported false alarms
         *  this change exists to fix, and the third's track still gets a
         *  warning from another cue. Read that 2.0 m with its own metric in
         *  mind - closest approach there is measured to the RADAR, so for a
         *  vehicle converging on the front wheel it understates how close
         *  the car came to the bike, which is the point this whole scoring
         *  change rests on. That is the error budget, sampled rather than
         *  bounded: it says the intercept error did not swing a vehicle
         *  measured close to the radar across the line on those rides, not
         *  that it cannot.
         *
         *  **Two replays, two populations - do not read one as reproducing
         *  the other.** The sweep above reads both `.log` and `.log.gz` and
         *  covers the whole corpus. [CorpusReplayGate] is `.log`-only and
         *  compares against a stored baseline, so it sees a subset: measured
         *  against the PRE-CHANGE baseline this value moved 9 captures,
         *  removed 10 urgent cues, and left every beep and all-clear tally
         *  identical. That baseline has since been re-recorded, so running
         *  the gate today reproduces no diff - the figures above are the
         *  evidence for the change, not something the gate will re-derive.
         *  The gate is the runnable check; the sweep is where 16 of 100
         *  comes from.
         *
         *  **Do not lower the default without re-running both.** The corpus
         *  is private ride data, so neither replay is in this tree and CI
         *  runs neither; the in-repo cue-ledger fixture reaches no urgent
         *  cue and cannot stand in
         *  ([CueLedgerReplayTest.passClearanceIsNoOpForThisFixture] pins
         *  that). A rider may of course set it lower; the setting is theirs,
         *  and the helper text says which way it trades. */
        const val DEFAULT_PASS_CLEARANCE_M = 1.5f

        /** Bounds for the rider-set clearance, kept beside the default so the
         *  three cannot drift apart across files. The bottom reaches the
         *  predicted-hit path (a fitted line crossing the centreline inside
         *  the bike scores zero), which only changes an outcome below a metre;
         *  the top reaches a near-equivalent of the pre-change behaviour -
         *  near, not identical, since over the corpus 2.5 m of envelope
         *  clearance differed from [PassScoring.RADAR_POINT] by 3 cues in 100. */
        const val MIN_PASS_CLEARANCE_M = 0.5f
        const val MAX_PASS_CLEARANCE_M = 3.0f

        /** Minimum samples in a track's lateral history before its
         *  predicted-pass intercept is trusted. */
        const val URGENT_PASS_FIT_MIN_POINTS = 5

        /** Minimum approach span (max - min distanceM, metres) the
         *  history must cover before the intercept is trusted; an
         *  extrapolation from a narrow distance band amplifies the
         *  radar's ~1 m lateral jitter into metres at distance 0. */
        const val URGENT_PASS_FIT_MIN_SPAN_M = 6

        /** Cap on stored lateral samples per track. Samples are deduped (a
         *  repeat of the tail refreshes its timestamp), so this counts
         *  DISTINCT measurements - several seconds of approach at the
         *  radar's target cadence, deep enough that the fallback fit still
         *  sees the run's spread after the track bunches near the pass
         *  point. Staleness inside this window is handled where it can do
         *  harm: the fallback fit may only veto with corroboration from the
         *  newest sample (see [predictedPassFit]). Swerve reactivity is not
         *  governed by this cap but by [URGENT_PASS_RECENT_FIT_WINDOW]. */
        const val URGENT_PASS_HISTORY_MAX = 48

        /** Newest samples the primary pass-prediction fit uses. Kept
         *  short so a genuine swerve toward the rider shrinks the
         *  predicted offset and re-arms the cue within a couple of
         *  frames; the full retained history is consulted only when
         *  this window is too span-poor to judge. */
        const val URGENT_PASS_RECENT_FIT_WINDOW = 12

        /** Sighting gap (ms) that resets a track's lateral history. The
         *  radar reuses track ids; a gap this long means the id came
         *  back as a different car, and stitching the runs together
         *  would corrupt the fit. Matches the segment-split heuristic
         *  used in the offline capture analysis. */
        const val URGENT_PASS_HISTORY_RESET_MS = 1_500L

        /** Quiet gap (ms, no urgent-qualifying target) after which the
         *  urgent episode lapses and the next fire is a fresh first
         *  warning. Longer than the widest inter-car gap observed inside
         *  a captured platoon storm (~4.9 s), so a light-released
         *  stream stays one episode; far shorter than the lull between
         *  genuinely separate encounters (38 s in the same capture). */
        const val URGENT_EPISODE_GAP_MS = 6_000L

        /** Pacing (ms) for urgent re-fires within one episode. The cue
         *  still repeats while an imminent condition is held - the
         *  alarm-standards pattern - but at a rate a rider can parse:
         *  a true stationary-rider threat resolves (impact or pass)
         *  within ~4 s of first qualifying, so 3 s yields the initial
         *  warning plus at most one repeat per real threat, while a
         *  platoon storm collapses from one volley per car to one cue
         *  per pacing window. Aligned with [TTC_GATE_SECONDS]. */
        const val URGENT_EPISODE_REPEAT_MS = 3_000L

        /** Closing-speed margin (m/s) over the episode's peak that lets a
         *  candidate bypass the episode pacing: closing this much faster
         *  than anything already observed this episode is new severity, not a
         *  re-statement (IEC 60601-1-8 "new condition overrides pause").
         *  2 m/s = 4 radar quanta - real, not jitter. */
        const val URGENT_NEW_THREAT_CLOSING_DELTA_MS = 2f
    }
}
