// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import es.jjrh.bikeradar.RadarLightMode

/**
 * How the bound service reaches the radar link, which lives in a different
 * component and only exists while a ride is running.
 *
 * [install] and [reset] rather than a public mutable lambda: the handler
 * captures the live GATT connection, so a stale one left behind after the ride
 * service dies would either write to a dead link or hold it from being
 * collected. The owner installs on connect and resets on teardown, and the
 * absence of a handler is the honest answer "no radar to talk to" rather than
 * a silent no-op.
 *
 * One slot, last writer wins, and that is only safe because no two connection
 * attempts overlap: the reconnect loop is a single sequential coroutine, its
 * `finally` resets before the next attempt installs, and `closeOnce` is behind
 * a guard so a late callback from a superseded GATT cannot wipe a newer
 * handler. A second concurrent link would need a generation token here.
 */
object RadarControlBridge {

    @Volatile
    private var handler: ((RadarLightMode) -> Boolean)? = null

    /** Called by the radar link when it has a live connection. */
    fun install(setLightMode: (RadarLightMode) -> Boolean) {
        handler = setLightMode
    }

    /** Called on teardown. A later [set] then reports failure rather than lying. */
    fun reset() {
        handler = null
    }

    /**
     * False when no link is installed, and otherwise whatever the installed
     * handler answers.
     *
     * That handler folds several outcomes into the one boolean - the link torn
     * down mid-call, the radio refusing the write, the queue's own per-op
     * timeout, and giving up on a write that may still land - and catches its
     * own throws, so nothing crosses this as an exception. A consumer cannot
     * tell those apart, so it should say "could not set it" rather than naming
     * a cause.
     */
    fun set(mode: RadarLightMode): Boolean = handler?.invoke(mode) ?: false

    /**
     * Whether a link is available to act on. Read only by tests today; the
     * diagnostic bundle does not carry it, so do not cite this as a field a
     * rider's report would show.
     */
    val available: Boolean get() = handler != null
}
