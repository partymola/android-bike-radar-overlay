// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Global switch controlling whether the on-screen [RadarOverlayView] is shown.
 * Defaults to visible; remote callers (via [RadarIpcService]) flip it to hide
 * the visual overlay while the audio alert cues keep running. Consumed by
 * [OverlayPipeline] every frame.
 */
object RadarOverlayGate {
    @Volatile
    var visible: Boolean = true
}
