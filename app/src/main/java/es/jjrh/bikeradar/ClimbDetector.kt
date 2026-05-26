// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Classify "rider is climbing" from sustained rider-effort. Used by
 * the AlertDecider stationary override to override the low-speed
 * suppress on a climb (a rider grinding up at 5 km/h is not at a
 * traffic light; alerts must still fire).
 *
 * Threshold: rider must produce at least [DEFAULT_THRESHOLD_W] watts of
 * pedal effort continuously for at least [DEFAULT_SUSTAIN_MS]
 * milliseconds. 250 W sustained for 30 s is well above commuting cruise
 * (50-150 W) and well above the bike's motor-assist baseline of zero
 * (rider_power on the eBike v1 spec is rider effort only, not motor
 * assist). 30 s damps single-frame spikes (a hard push-off, a power
 * spike on a kerb).
 *
 * The detector is stateless; the caller threads [State] across calls.
 * Designed to be invoked once per eBike snapshot from the BLE callback.
 */
object ClimbDetector {

    const val DEFAULT_THRESHOLD_W: Int = 250
    const val DEFAULT_SUSTAIN_MS: Long = 30_000L

    /**
     * @param highPowerStartedMs Monotonic ms of the first frame in the
     *   current high-power run, or null when the most recent frame was
     *   below the threshold.
     */
    data class State(val highPowerStartedMs: Long? = null)

    /**
     * Apply one snapshot. Returns the next [State] and whether the rider
     * is currently in a sustained climb (true once [sustainMs] has
     * elapsed in a continuous run above [thresholdW]).
     *
     * @param riderPowerW From eBike snapshot, in watts. Null when eBike is
     *   not bonded or rider_power has not yet appeared in any NOTIFY.
     *   Null => not climbing, state reset.
     */
    fun classify(
        prev: State,
        nowMs: Long,
        riderPowerW: Int?,
        thresholdW: Int = DEFAULT_THRESHOLD_W,
        sustainMs: Long = DEFAULT_SUSTAIN_MS,
    ): Pair<State, Boolean> {
        if (riderPowerW == null || riderPowerW < thresholdW) {
            return State(highPowerStartedMs = null) to false
        }
        val startedMs = prev.highPowerStartedMs ?: nowMs
        val sustained = nowMs - startedMs >= sustainMs
        return State(highPowerStartedMs = startedMs) to sustained
    }
}
