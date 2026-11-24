// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import es.jjrh.bikeradar.RadarLinkVisualDecider.LinkVisual

/**
 * The service-owned dead-radar banner's window. Main thread only.
 *
 * A visual is decided off Main and applied here later, so a tick's decision
 * taken just before the radar's first frame can arrive after that frame's
 * LIVE. While [radarStreaming] holds, every visual is applied as LIVE, since
 * each other one says the rear has no cover
 * (`ReconnectBannerTest.aDecisionFromBeforeTheFirstFrameDoesNotOutliveIt`).
 * The state is read here, at apply time, not where the visual is posted, and
 * the first frame posts its LIVE only after recording the stream
 * (`theFirstFramePostsItsLiveOnlyOnceTheStreamIsRecorded`).
 */
internal class ReconnectBanner(
    private val host: OverlayHost,
    private val radarStreaming: () -> Boolean,
    private val clog: (String) -> Unit,
    private val journal: (String) -> Unit,
) {
    private var view: RadarOverlayView? = null

    // Logged when the kind of failure changes, as the strip's attach is: a
    // failing attach is retried on every tick of the drop.
    private var lastAttachFailure: String? = null

    /** LIVE detaches; another visual attaches the banner, or retitles the one
     *  already up. */
    fun show(posted: LinkVisual) {
        val visual = if (radarStreaming()) LinkVisual.LIVE else posted
        val current = view
        if (visual == LinkVisual.LIVE) {
            lastAttachFailure = null
            if (current != null) {
                view = null
                host.detach(current)
                clog("# reconnect_banner hidden")
            }
            return
        }
        if (current != null) {
            current.setReconnecting(visual)
            return
        }
        val v = if (host.canDrawOverlays()) host.createView() else null
        v?.setReconnecting(visual)
        val error = v?.let { host.attach(it) }
        val failure = when {
            v == null -> "no-permission"
            error != null -> error.javaClass.name
            else -> null
        }
        // Unless connection setup is being recorded, the capture log is closed
        // while the radar is down, so the journal carries the failure too.
        when {
            failure == null -> {
                view = v
                clog("# reconnect_banner shown variant=$visual")
            }
            failure == lastAttachFailure -> {}
            error != null -> {
                clog("# reconnect_banner addView failed: $error")
                journal("reconnect banner not shown: ${error.javaClass.simpleName}")
            }
            else -> {
                clog("# reconnect_banner: SYSTEM_ALERT_WINDOW not granted")
                journal("reconnect banner not shown: overlay permission not granted")
            }
        }
        lastAttachFailure = failure
    }

    /** Takes the banner down with no capture line, for the service's stop. */
    fun remove() {
        view?.let { host.detach(it) }
        view = null
    }
}
