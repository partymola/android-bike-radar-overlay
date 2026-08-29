// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Formats what a radar connection attempt found - the discovered GATT table and
 * the step the handshake stopped at - into one line short enough to paste into
 * a bug report.
 *
 * It exists because neither existing sink answers "why did this radar never go
 * live?". The capture log opens only after a successful handshake, so a device
 * that never gets that far produces no file at all. The always-on journal does
 * record the abort, but it cannot carry the discovered table: it is one short
 * line per event, and a link that aborts every 1.5 s pushes everything else out
 * of the newest lines the diagnostic bundle prints. The caller stores this in
 * prefs instead, one slot, rewritten only when the content changes.
 *
 * Services and characteristics are named by the four hex digits the capture log
 * already uses, so two 128-bit UUIDs sharing those digits read the same here.
 * That is the existing convention across the capture log and the handshake
 * script lines, and spelling UUIDs out in full would put the line well past what
 * a rider will paste.
 *
 * Public-tracker safety is a property of the CALLER, not of this formatter,
 * which returns whatever it is handed: the caller passes only four-hex UUID
 * tags and a fixed outcome token, never an address, a device name or packet
 * content. Two pins hold that: the controller harness asserts the exact stored
 * line for a device with a name, and the Prefs dump test seeds an
 * address-shaped probe value and asserts the dump strips it.
 */
internal object LinkProbe {

    /**
     * Budget for the service list alone. The outcome is appended afterwards and
     * is never dropped: it is the one field that says what went wrong, and a
     * device with an unusually large GATT table is exactly the case where it
     * matters most.
     */
    const val MAX_SERVICE_CHARS = 700

    /**
     * @param services discovered services in discovery order, each as its
     *   four-digit tag paired with its characteristics' tags.
     * @param outcome the handshake's stopping point, or `handshake-ok`.
     */
    fun format(services: List<Pair<String, List<String>>>, outcome: String): String {
        val tokens = services.map { (svc, chars) -> "$svc[${chars.joinToString(",")}]" }
        val kept = mutableListOf<String>()
        var used = 0
        for (token in tokens) {
            val cost = if (kept.isEmpty()) token.length else token.length + 1
            if (used + cost > MAX_SERVICE_CHARS) break
            kept += token
            used += cost
        }
        val dropped = tokens.size - kept.size
        val body = when {
            tokens.isEmpty() -> "none"
            kept.isEmpty() -> "+$dropped more"
            dropped > 0 -> kept.joinToString(" ") + " +$dropped more"
            else -> kept.joinToString(" ")
        }
        return "svc=$body out=$outcome"
    }

    /** A stored probe line split back into its stamp and the [format] body. */
    data class Stored(val sinceMs: Long, val body: String)

    /** Render a stored line: [sinceMs] is when [body] was first seen. */
    fun render(sinceMs: Long, body: String): String = "$SINCE_PREFIX$sinceMs $body"

    /**
     * Read a stored line back, or null when it is absent or not in that shape.
     *
     * The caller uses this to seed its debounce at process start. Without it
     * the stamp means "first seen since the last reboot": the first attempt
     * after a restart rewrites the same answer with a fresh stamp, so a radar
     * that has been failing for weeks reports a few seconds, which is the
     * opposite of what the field is for.
     */
    fun parse(stored: String?): Stored? {
        val s = stored ?: return null
        if (!s.startsWith(SINCE_PREFIX)) return null
        val sep = s.indexOf(' ', SINCE_PREFIX.length)
        if (sep < 0) return null
        val ms = s.substring(SINCE_PREFIX.length, sep).toLongOrNull() ?: return null
        val body = s.substring(sep + 1)
        if (body.isEmpty()) return null
        return Stored(ms, body)
    }

    private const val SINCE_PREFIX = "since="
}
