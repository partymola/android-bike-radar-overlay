// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Redacts device identifiers on their way into logcat.
 *
 * Logcat is not private to the app. A release build writes to it like any
 * other, `adb bugreport` collects it wholesale, and a bug report is a file a
 * rider hands to someone else. A Bluetooth MAC is a durable identifier for a
 * specific piece of hardware, so it is the one field here worth withholding.
 *
 * The capture log is a different surface and already handles this its own way:
 * it is opt-in, it stays on the phone unless the rider shares it, and its
 * logcat mirror is debug-only. This object governs the logcat path, which has
 * no such gate.
 *
 * Why a helper rather than editing the call sites: there were eleven of them
 * across five files, and a convention holds only until the twelfth. A site
 * that logs an address and does not come through [mac] is the thing to catch
 * in review - grep for `.address` as well as `$mac`, because the first sweep
 * matched only the variable name and missed the site that inlined the
 * expression.
 *
 * Scope, stated because the obvious reading is wider: this withholds Bluetooth
 * addresses and nothing else. Device names still reach release logcat by
 * design, and so does a Home Assistant hostname when a connection fails,
 * because the exception message carries it.
 */
internal object LogRedaction {

    /** Placeholder written in place of an address a release build must not
     *  emit. Deliberately not an empty string: the field stays in the line so
     *  a reader sees a value was withheld rather than a log that never had
     *  one. */
    const val WITHHELD = "(mac)"

    /**
     * A Bluetooth address as it should appear in a logcat line.
     *
     * Debug builds get the real address, because a developer reading their own
     * device's log needs to tell two radios apart. Release builds get
     * [WITHHELD]. Device NAMES are left alone and still appear beside this in
     * most call sites, which is what keeps a release log diagnosable at all.
     */
    fun mac(mac: String?, debug: Boolean = BuildConfig.DEBUG): String = when {
        mac == null -> "-"
        debug -> mac
        else -> WITHHELD
    }
}
