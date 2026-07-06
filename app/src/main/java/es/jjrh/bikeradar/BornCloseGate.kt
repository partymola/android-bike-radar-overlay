// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Closing-evidence admission for born-close radar tracks - the audio-side
 * half of the ghost-beep filter (Settings -> Experimental).
 *
 * Problem: the radar sometimes births a track already inside the tier-3
 * band - a stationary roadside object swept through the beam during a
 * turn, or clutter alongside the rider - and the tier system, which maps
 * distance straight to beep count, opens the episode with an instant
 * close-range triple for something that is not a vehicle. Ride evidence:
 * a rider-confirmed false triple fired 179 ms after such a birth, from a
 * track that swept laterally through the beam mid-turn and died without
 * ever passing.
 *
 * Physical rationale: a genuine threat first seen at close range must be
 * closing hard - had it approached at ordinary speed the radar would have
 * acquired it far out (real vehicles are acquired at 30-80 m). So a track
 * that is BORN close earns its beeps by showing closing evidence:
 *
 *  - FAST: closing >= [fastClosingMs] on [evidenceFrames] consecutive
 *    frames, counted only while the rider is not actively turning.
 *    Rotation converts lateral offset into fake radial closing
 *    (dy/dt picks up an omega*x term - 2-3.5 m/s in an urban turn), so
 *    TURNING frames prove nothing at this bar. HOLD counts as clean:
 *    it lasts up to 10 s at near-zero yaw, where no geometric closing
 *    can be manufactured.
 *  - URGENT-GRADE: closing >= [urgentClosingMs] on [evidenceFrames]
 *    consecutive frames, in ANY turn state - turn geometry cannot fake
 *    this much closing, and a real fast closer born mid-turn must not
 *    wait for the corner to end.
 *  - SLOW: closing >= [slowClosingMs] held continuously for
 *    [slowDwellMs], not-turning frames only - admits genuine crawler
 *    overtakes (a car creeping past at walking pace) with a bounded
 *    delay instead of silencing them.
 *
 * Scope guarantees (the safety contract, pinned by tests):
 *  - This class only decides whether a would-be tier Beep is silenced.
 *    It never touches the all-clear presence gate, urgent evaluation,
 *    sustain counters, or the overlay - an unadmitted track stays fully
 *    visible everywhere except the tier-beep audio.
 *  - It only ever engages for tracks with [Vehicle.bornInformative]
 *    true and [Vehicle.bornDistanceM] <= [bornCloseMaxM]. Reacquired
 *    followers (coverage-gap rebirths) and warm-up births are marked
 *    uninformative by the decoder and pass through untouched.
 *
 * When a suppressed track later earns admission, [update] reports it so
 * the caller can re-arm the beep path and deliver the cue at the track's
 * CURRENT tier - the "car finally started closing" beep. One bounded
 * limitation: emission always describes the closest track, so if a
 * still-gated closer ghost coexists with the just-admitted farther track,
 * the re-armed cue stays silent until the admitted track produces its
 * next tier edge (it becomes the closest and its tier rises above the
 * last audible level). That direction only ever delays a tier beep - it can never fire a false cue or touch the all-clear/urgent
 * paths - matching the project's delayed-over-false asymmetry.
 *
 * Not thread-safe; call from the decider's single coroutine.
 */
class BornCloseGate(
    private val bornCloseMaxM: Int = BORN_CLOSE_MAX_M,
    private val fastClosingMs: Float = FAST_CLOSING_MS,
    private val urgentClosingMs: Float = URGENT_GRADE_CLOSING_MS,
    private val slowClosingMs: Float = SLOW_CLOSING_MS,
    private val slowDwellMs: Long = SLOW_DWELL_MS,
    private val evidenceFrames: Int = EVIDENCE_FRAMES,
    private val staleMs: Long = STATE_STALE_MS,
) {

    private class TrackState {
        var fastRun = 0
        var urgentRun = 0
        var slowRunSinceMs = Long.MIN_VALUE
        var admitted = false
        var suppressedPending = false
        var lastSeenMs = 0L
    }

    /** Keyed by (tid, bornAtMs): the radar reuses tids, and admission
     *  earned by one physical track must not leak to its successor. */
    private val states = HashMap<Pair<Int, Long>, TrackState>()

    /**
     * Feed this frame's vehicles. Returns the tids of tracks admitted on
     * THIS frame that had a suppressed beep pending - the caller should
     * re-arm the beep path for them.
     */
    fun update(
        vehicles: List<Vehicle>,
        turnState: TurnStateDecider.State,
        nowMs: Long,
    ): List<Int> {
        val refire = mutableListOf<Int>()
        val turning = turnState == TurnStateDecider.State.TURNING
        for (v in vehicles) {
            if (!appliesTo(v)) continue
            val st = states.getOrPut(v.id to v.bornAtMs) { TrackState() }
            st.lastSeenMs = nowMs
            if (st.admitted) continue
            val closing = -v.speedMs
            val clean = !turning
            st.fastRun = if (closing >= fastClosingMs && clean) st.fastRun + 1 else 0
            st.urgentRun = if (closing >= urgentClosingMs) st.urgentRun + 1 else 0
            if (closing >= slowClosingMs && clean) {
                if (st.slowRunSinceMs == Long.MIN_VALUE) st.slowRunSinceMs = nowMs
            } else {
                st.slowRunSinceMs = Long.MIN_VALUE
            }
            val slowElapsed = st.slowRunSinceMs != Long.MIN_VALUE &&
                nowMs - st.slowRunSinceMs >= slowDwellMs
            if (st.fastRun >= evidenceFrames || st.urgentRun >= evidenceFrames || slowElapsed) {
                st.admitted = true
                if (st.suppressedPending) {
                    st.suppressedPending = false
                    refire.add(v.id)
                }
            }
        }
        states.values.removeAll { nowMs - it.lastSeenMs > staleMs }
        return refire
    }

    /** True when [v]'s tier beep must stay silent: an informative
     *  born-close track that has not yet shown closing evidence. */
    fun isGated(v: Vehicle): Boolean {
        if (!appliesTo(v)) return false
        val st = states[v.id to v.bornAtMs] ?: return true
        return !st.admitted
    }

    /** Record that a would-be beep for [v] was silenced, so admission can
     *  re-arm the cue later. */
    fun noteSuppressed(v: Vehicle) {
        if (!appliesTo(v)) return
        states[v.id to v.bornAtMs]?.let { it.suppressedPending = true }
    }

    /** Episode ended (all-clear fired): pending re-fires are stale, but
     *  admission itself survives - a track that earned its voice keeps it
     *  for its lifetime. */
    fun onClear() {
        states.values.forEach { it.suppressedPending = false }
    }

    fun reset() = states.clear()

    private fun appliesTo(v: Vehicle): Boolean = v.bornInformative && v.bornDistanceM <= bornCloseMaxM

    companion object {
        /** Born-at-or-inside this distance = suspect birth. Covers the
         *  tier-3 band (10 m at the shipped alertMax 30) with margin. */
        const val BORN_CLOSE_MAX_M = 12

        /** Ordinary closing-evidence bar. Turn-sweep ghosts measured
         *  2-3.5 m/s of geometric closing mid-turn, hence this bar only
         *  counts on not-TURNING frames. */
        const val FAST_CLOSING_MS = 2.5f

        /** Closing this hard cannot be manufactured by turn geometry;
         *  admits in any turn state so a real fast closer born mid-turn
         *  alerts immediately. Matches the urgent path's stationary
         *  closing floor. */
        const val URGENT_GRADE_CLOSING_MS = 6f

        /** Slow-path bar: genuine crawler overtakes close at >= 1 m/s
         *  sustained; clutter and matched-speed non-threats do not. */
        const val SLOW_CLOSING_MS = 1f

        /** How long the slow-path closing must be held. */
        const val SLOW_DWELL_MS = 1500L

        /** Consecutive frames for the fast/urgent bars (~90 ms/frame). */
        const val EVIDENCE_FRAMES = 2

        /** Drop per-track state this long after the track vanishes. */
        const val STATE_STALE_MS = 2000L
    }
}
