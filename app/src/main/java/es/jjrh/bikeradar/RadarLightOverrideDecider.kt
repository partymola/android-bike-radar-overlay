// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Pure logic for "did the rider take manual control of the radar tail light".
 *
 * The radar reports mode-state on 6a4e2f14 as its SELECTED SLOT, not our
 * type-override (see [RadarLightController]). The app never moves the selected
 * slot - it only issues type-overrides - so a CHANGE in the reported slot
 * mode-state means the rider moved it themselves (a physical button press, or a
 * vendor-app slot change). That is the override signal. The comparison is
 * against a per-connect BASELINE (the first mode-state seen after subscribing
 * on this connection), NOT against what we wrote - the dashcam's
 * "device != lastWritten" test cannot be used here because a type-override
 * never updates 2f14.
 *
 * The key folds slot AND type ((slot shl 8) or type) so a button press that
 * moves between two slots holding the same mode type is still detected.
 */
object RadarLightOverrideDecider {

    /** Key for a 2f14 mode-state: slot ordinal in the high byte, type in the low. */
    fun key(slot: Int, type: Int): Int = ((slot and 0xFF) shl 8) or (type and 0xFF)

    /**
     * True when [observedKey] differs from the per-connect [baselineKey] - the
     * rider moved the selected slot. A null baseline (not yet captured this
     * connect) is never an override.
     */
    fun isOverride(baselineKey: Int?, observedKey: Int): Boolean = baselineKey != null && observedKey != baselineKey

    /**
     * Clear a session override only after the radar link has been down longer
     * than [deadbandMs]: a brief reconnect keeps the override (don't fight the
     * rider's deliberate choice across a blip), but ending the ride / a long gap
     * re-arms auto-mode. Mirrors the dashcam's override deadband. A null
     * [offSinceMs] (never disconnected this session) never clears.
     */
    fun shouldClearOverride(offSinceMs: Long?, nowMs: Long, deadbandMs: Long): Boolean = offSinceMs != null && nowMs - offSinceMs >= deadbandMs
}
