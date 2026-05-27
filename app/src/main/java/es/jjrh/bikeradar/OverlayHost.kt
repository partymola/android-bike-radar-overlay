// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Side-effect boundary for the [OverlayPipeline]'s window-manager interactions
 * and the system-overlay permission check, so the pipeline class itself is
 * JVM-constructible without an Android [android.content.Context]. Production
 * code wires [AndroidOverlayHost] in [BikeRadarService]; tests pass a
 * no-op stub.
 */
internal interface OverlayHost {
    /** Create the per-connection overlay [RadarOverlayView]. The pipeline
     *  re-creates the view on every [OverlayPipeline.attach] - this matches
     *  the original inline behaviour where view lifetime was bounded by the
     *  enclosing coroutine. */
    fun createView(): RadarOverlayView

    /** True if the user has granted SYSTEM_ALERT_WINDOW. Checked at the
     *  start of every frame because the OS can revoke it asynchronously. */
    fun canDrawOverlays(): Boolean

    /** Add [view] to the window manager. Returns null on success, or the
     *  caught throwable on failure (typically a SYSTEM_ALERT_WINDOW TOCTOU
     *  revocation between [canDrawOverlays] and the addView call). The
     *  caller folds the throwable into the capture log so a missing overlay
     *  is diagnosable from a post-hoc log pull. */
    fun attach(view: RadarOverlayView): Throwable?

    /** Remove [view] from the window manager. Best-effort: catches removal
     *  exceptions (the view may have been detached by the OS already). */
    fun detach(view: RadarOverlayView)

    /** Re-apply window-manager layout params for the currently-attached
     *  view (rotation handler). No-op if nothing is attached. The host owns
     *  the LayoutParams construction so the service doesn't need to. */
    fun onConfigurationChanged()
}

/**
 * Cached phone-battery snapshot sampled from the sticky
 * `ACTION_BATTERY_CHANGED` broadcast. Pulled out of the [OverlayPipeline]'s
 * frame loop so the class doesn't reach into Context for a registerReceiver
 * call. Production impl returns a fresh sample on each invocation; tests
 * return a fixed snapshot or null.
 */
internal interface PhoneBatterySource {
    fun readSnapshot(): PhoneBatteryReading?
}

internal data class PhoneBatteryReading(
    val level: Int,
    val scale: Int,
    val tempDc: Int,
    val plugged: Int,
)
