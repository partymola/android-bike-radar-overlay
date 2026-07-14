// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * "Is the rider actually riding right now?", decided from the eBike's own live
 * speed. Pure state machine; the caller ([EBikeSnapshotCoordinator]) threads
 * [State] across snapshots and asks [ridingFresh] per tick.
 *
 * Why it exists: the radar-drop cue used to confirm riding from the eBike's
 * `system_locked == false` alone. But an eBike reports itself unlocked the
 * moment it is switched on - in a garage, in an office bike store, on a rack -
 * so a rider powering the bike up while the radar is still in a pannier looked
 * exactly like a rider mid-ride whose radar link had just died. The cue fired
 * indoors, on every power-on, and repeated on cadence until the radar came up.
 * Unlock state says the bike is AWAKE; only speed says the rider is RIDING.
 *
 * Two guards, both load-bearing, because the rider handles a powered-on bike
 * well before setting off:
 *
 *  - **Sustain** ([SUSTAIN_MS]): speed must stay above [WALKING_PACE_MS] for a
 *    continuous spell before it counts as riding. Wheeling a bike out of a rack
 *    runs ~1-1.5 m/s (under the floor already), but a short roll down a ramp or
 *    a scoot across a yard can tick over it for a second or two. Those must not
 *    confirm a ride. Any drop back to walking pace resets the spell, so the
 *    sustain cannot be accumulated in dribs and drabs.
 *  - **Freshness** ([FRESH_MS], applied in [ridingFresh]): confirmation expires
 *    a while after the rider stops, so a bike sitting parked and awake stops
 *    counting as ridden.
 *
 * SUSTAIN IS THE GUARD THAT BUYS THE INDOOR SILENCE, NOT THE EXPIRY. That split
 * matters, because the two guards have opposite error costs. A missed drop cue
 * leaves the rider blind behind - the worst failure this feature has - so the
 * expiry must be generous enough to survive everything that legitimately
 * interrupts a ride:
 *
 *  - **Long red light / stop-start traffic**: the rider is stopped for minutes,
 *    still mid-ride. Confirmation has to outlive the stop, or a radar that dies
 *    at a light is never announced.
 *  - **Slow climb**: a loaded bike grinding uphill can sit under [WALKING_PACE_MS]
 *    for a spell. That is riding, and confirmation survives up to [FRESH_MS] of
 *    it - long past anything an assisted bike realistically spends below 7.2 km/h.
 *
 * The expiry is therefore [FRESH_MS], not the tight window the indoor bug might
 * suggest - a short expiry would have swapped a false cue for a MISSED one,
 * which is the wrong side of the asymmetry. Silence in the garage comes from the
 * sustain guard instead: handling a bike never produces a continuous
 * above-pace spell, so `lastRidingMs` is never set at all and no expiry is even
 * consulted.
 *
 * Residual, accepted: a rider who stops for longer than [FRESH_MS] mid-ride and
 * whose radar dies during that stop hears nothing until they set off again, at
 * which point the sustain re-confirms within seconds and the cue fires. A cue a
 * few seconds into the resumed ride, rather than at a standstill, is the better
 * trade - and the rider is not exposed while stationary.
 *
 * A snapshot with no speed field ([next] called with a null speed) carries NO
 * movement information: it neither confirms riding nor resets the spell, so a
 * sparse frame in a stream of moving ones cannot silently break the sustain.
 */
object RidingSpeedGate {

    /** Bike speed above this (m/s) is riding, not wheeling. 2.0 m/s = 7.2 km/h,
     *  comfortably above a walked bike and below any real riding pace. The
     *  radar-only path's `RadarLinkCoordinator.RADAR_DROP_WALKING_PACE_MS` reads
     *  this constant, so the two cohorts share one definition of "walking pace"
     *  by construction rather than by two literals kept equal by hand. */
    const val WALKING_PACE_MS = 2.0f

    /** Continuous above-walking-pace spell needed before riding is confirmed.
     *  Long enough that a roll out of a rack or a scoot to the road never
     *  qualifies; short enough that a real ride confirms within the first few
     *  crank turns. */
    const val SUSTAIN_MS = 10_000L

    /** How long a confirmed ride survives a stop or a spell below walking pace.
     *  Sized for the things that legitimately interrupt a ride - a long light, a
     *  queue, a slow climb - because a MISSED drop cue is the worse error. It is
     *  NOT what keeps the cue quiet indoors (the sustain guard is), so it can
     *  afford to be generous. A parked, unlocked bike stops being confirmed once
     *  this expires; an explicit lock vetoes the cue outright before then. */
    const val FRESH_MS = 120_000L

    /**
     * @param movingSinceMs start of the current continuous above-pace spell, or
     *   null when the bike is at/below walking pace.
     * @param lastRidingMs monotonic instant riding was last confirmed (the
     *   sustain spell had been met), or null if never this session.
     */
    data class State(
        val movingSinceMs: Long? = null,
        val lastRidingMs: Long? = null,
    )

    /** Fold one snapshot's speed into the state. [speedMs] null = no movement
     *  information in this frame: return [prev] untouched. */
    fun next(
        prev: State,
        nowMs: Long,
        speedMs: Float?,
        walkingPaceMs: Float = WALKING_PACE_MS,
        sustainMs: Long = SUSTAIN_MS,
    ): State {
        if (speedMs == null) return prev
        if (speedMs <= walkingPaceMs) return prev.copy(movingSinceMs = null)
        val since = prev.movingSinceMs ?: nowMs
        val sustained = nowMs - since >= sustainMs
        return State(
            movingSinceMs = since,
            lastRidingMs = if (sustained) nowMs else prev.lastRidingMs,
        )
    }

    /** Whether riding was confirmed recently enough to still believe it.
     *  Boundary: age == [freshMs] is NOT fresh (strict `<`), matching the eBike
     *  snapshot-freshness convention. */
    fun ridingFresh(state: State, nowMs: Long, freshMs: Long = FRESH_MS): Boolean {
        val last = state.lastRidingMs ?: return false
        return nowMs - last < freshMs
    }

    /** Bosch reports speed raw in 1/100 km/h; null stays null. */
    fun speedMs(speedRaw: Int?): Float? = speedRaw?.let { it / 360f }
}
