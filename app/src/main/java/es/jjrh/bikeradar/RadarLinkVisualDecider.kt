// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Decides whether the overlay should show a "rear radar disconnected" banner
 * while the rear-radar BLE link is down, and which message to use. Pure
 * function; stateless (the down-duration latch lives in the caller via
 * `radarOffSinceMs`) so it can be called every tick and reflected on screen.
 *
 * Why it exists: when the radar drops mid-ride the per-connection overlay
 * pipeline is torn down (its view detaches), so the screen stops showing rear
 * traffic with no indication that the channel is dead - a frozen/blank overlay
 * reads as "clear road". This banner says the rear is blind, so silence on the
 * screen stops being ambiguous.
 *
 * ## Cohort-aware, and why this REVERSED the original "never gate the visual"
 * The banner is a persistent WindowManager overlay drawn over whatever app the
 * rider has open. The first design showed it indefinitely (a pure down-duration
 * timeout, no rider-state gate) on the theory that a radar-only rider's only
 * dead-radar signal must never be hidden. In practice an overlay that never
 * retires makes the phone unusable and drives uninstalls - and an uninstalled
 * app protects nobody. So the banner is now bounded, and uses the eBike lock
 * state (when present) to bound it smartly:
 *
 * - **eBike riders** ([hasEBikeSignal] true - a Bosch eBike snapshot has been
 *   seen this session): the banner shows while the bike is NOT explicitly parked
 *   and hides the moment it is locked ([explicitParked]). It is bounded by a
 *   generous [ebikeMaxMs] (a forgot-to-lock backstop) - safe to bound because
 *   this rider also gets the repeating audio drop cue. The banner hides whenever
 *   the last-known reading is locked ([explicitParked]) - locked is sticky across
 *   staleness, because locking the bike is itself what drops the eBike link, so
 *   the lock reading inevitably ages out. A stale snapshot whose last reading was
 *   UNLOCKED does NOT hide it, so a simultaneous mid-ride Flow+radar dropout
 *   doesn't blind the rider - the visual stays on its own failure mode, not
 *   coupled to the audio cue's. This banner doubles as a "you walked off without
 *   locking" alert, the one case the walk-away alarm stays silent for (it never
 *   arms while unlocked).
 * - **Radar-only riders** ([hasEBikeSignal] false - no eBike lock signal to
 *   consult): the banner shows from the threshold and retires after
 *   [radarOnlyMaxMs]. This is a deliberate, documented tradeoff: a radar-only
 *   rider whose radar dies mid-ride loses the visual after the cap (they get no
 *   audio cue either). It is accepted because (a) a permanent overlay is the
 *   bigger harm, (b) their built-in fixed rear light - the primary rear signal -
 *   is unaffected, and (c) [radarOnlyPersistent] is an opt-in escape hatch for
 *   the safety-maximalist radar-only rider. The cap is per down-episode, so a
 *   flapping radar re-shows the banner on each fresh drop.
 *
 * Threshold rationale: reconnects normally resolve in 5-10s (corpus median
 * 8.4s, hard floor 5.3s), so a ~10s threshold rides through normal reconnects
 * and only marks the screen blind once a drop is likely real. The screen
 * auto-clears the instant the radar returns ([radarDownForMs] back to null).
 *
 * ## Bench-test suppression ([everSawTrack])
 * The banner stays hidden until the radar has decoded at least one vehicle this
 * session. If it has connected but NEVER seen any traffic, the rider is almost
 * certainly not out on a road (a bench test at home, an empty test) - so a
 * dropped radar is not worth a banner. This is a session-CUMULATIVE proxy for
 * "actually riding" off the radar's own already-decoded targets (no sensor, no
 * permission); once any vehicle is seen the flag sticks for the rest of the
 * session. Safety edge: a real ride whose radar dies BEFORE it ever sees a car,
 * then meets traffic still down, gets no banner until reconnect - accepted
 * because zero-traffic-so-far is the lowest-risk blind-rear window and it
 * self-corrects the instant a vehicle is detected.
 */
object RadarLinkVisualDecider {

    /** Banner state + which message to render. PLAIN omits the lock-state line;
     *  UNLOCKED appends "but bike unlocked" (eBike rider, bike not parked). */
    enum class LinkVisual { LIVE, RECONNECTING_PLAIN, RECONNECTING_UNLOCKED }

    /**
     * @param radarEverLive whether the radar has connected at least once this
     *   session. A cold start (never connected) stays [LinkVisual.LIVE] - there
     *   was no link to lose, so a banner would be a lie.
     * @param everSawTrack whether the radar has decoded >=1 vehicle this session
     *   (sticky). False -> [LinkVisual.LIVE]: no traffic ever seen means a bench
     *   test, not a ride. See the class KDoc.
     * @param radarDownForMs how long the radar has been continuously down, or
     *   null when it is currently connected. Doubles as the per-episode
     *   display-age clock for the caps (it resets to null on reconnect).
     * @param visualThresholdMs how long down before the screen is marked blind.
     * @param paused whether the rider has paused alerts. Paused stays
     *   [LinkVisual.LIVE] so the banner never appears while the app is muted.
     * @param hasEBikeSignal whether a Bosch eBike snapshot has ever been seen
     *   this session (sticky). Selects the eBike vs radar-only path + message.
     * @param explicitParked the last-known eBike reading is system_locked == true
     *   (locked is sticky across snapshot staleness - see class KDoc). Maps to
     *   [LinkVisual.LIVE] (banner hidden); a last-known-UNLOCKED snapshot, fresh
     *   or stale, does not.
     * @param ebikeMaxMs down-duration after which the eBike banner retires even
     *   if still unlocked (forgot-to-lock backstop; the audio cue continues).
     * @param radarOnlyMaxMs down-duration after which the radar-only banner
     *   retires, unless [radarOnlyPersistent].
     * @param radarOnlyPersistent rider opted into keeping the radar-only banner
     *   up until the radar reconnects (removes [radarOnlyMaxMs]).
     */
    fun decide(
        radarEverLive: Boolean,
        everSawTrack: Boolean,
        radarDownForMs: Long?,
        visualThresholdMs: Long,
        paused: Boolean,
        hasEBikeSignal: Boolean,
        explicitParked: Boolean,
        ebikeMaxMs: Long,
        radarOnlyMaxMs: Long,
        radarOnlyPersistent: Boolean,
    ): LinkVisual {
        if (paused || !radarEverLive || !everSawTrack || radarDownForMs == null || radarDownForMs < visualThresholdMs) {
            return LinkVisual.LIVE
        }
        return if (hasEBikeSignal) {
            when {
                explicitParked -> LinkVisual.LIVE
                radarDownForMs >= ebikeMaxMs -> LinkVisual.LIVE
                else -> LinkVisual.RECONNECTING_UNLOCKED
            }
        } else {
            when {
                !radarOnlyPersistent && radarDownForMs >= radarOnlyMaxMs -> LinkVisual.LIVE
                else -> LinkVisual.RECONNECTING_PLAIN
            }
        }
    }
}
