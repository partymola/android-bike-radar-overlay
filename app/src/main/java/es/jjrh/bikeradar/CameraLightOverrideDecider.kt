// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Pure logic for "did the rider take manual control of the front camera light".
 *
 * Unlike the radar tail light, the front camera DOES echo the mode we wrote on
 * its mode-state notify (6a4e2f14), so the override signal is a device-reported
 * mode that differs from the last mode WE wrote - a physical side-button press.
 * (The radar can't use this test: a radar type-override never updates 2f14 - see
 * [RadarLightOverrideDecider], which compares against a per-connect baseline
 * instead.)
 */
object CameraLightOverrideDecider {

    /**
     * True when the [observed] device mode differs from the [expected] mode we
     * last wrote - the rider pressed the side button. A null [expected] (nothing
     * written yet this connect) is never an override.
     */
    fun isOverride(expected: CameraLightMode?, observed: CameraLightMode): Boolean = expected != null && observed != expected

    /**
     * Clear a session override only after the front link has been down longer
     * than [deadbandMs]: a brief reconnect keeps the override across a blip
     * (don't fight the rider's deliberate choice), but a long gap / ride end
     * re-arms auto-mode. A null [offSinceMs] (never disconnected) never clears.
     */
    fun shouldClearOverride(offSinceMs: Long?, nowMs: Long, deadbandMs: Long): Boolean = offSinceMs != null && nowMs - offSinceMs >= deadbandMs
}
