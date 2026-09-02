// SPDX-License-Identifier: GPL-3.0-or-later
// Additional permission for cross-app consumers: additional-permission.txt
package es.jjrh.bikeradar.ipc

import es.jjrh.bikeradar.RadarLightMode

/**
 * How the bound service reaches the radar link, which lives elsewhere and only
 * exists while a ride is running.
 *
 * [install] and [reset] rather than a mutable lambda: the handler captures the
 * live GATT connection, so a stale one would write to a dead link or hold it
 * from collection. No handler is the honest answer "no radar to talk to".
 *
 * One slot, last writer wins, which is safe only because no two connection
 * attempts overlap: the reconnect loop is a single sequential coroutine, its
 * `finally` resets before the next attempt installs, and `closeOnce` is behind
 * a guard so a late callback from a superseded GATT cannot wipe a newer
 * handler. A second concurrent link would need a generation token.
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
     * False when no link is installed, else whatever the handler answers. That
     * handler, in `RadarLinkController`, folds several outcomes into the one
     * boolean and catches its own throws, so nothing crosses here as an
     * exception and a consumer should say "could not set it" rather than name a
     * cause. `RadarLightWriteForGrantedAppTest` pins the catching.
     */
    fun set(mode: RadarLightMode): Boolean = handler?.invoke(mode) ?: false

    /**
     * Whether a link is available to act on. Read only by tests; the diagnostic
     * bundle does not carry it.
     */
    val available: Boolean get() = handler != null
}
