// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Remembers where a BLE link stopped, and for how long it has been stopping
 * there, for the diagnostic bundle.
 *
 * One recorder per link. The rear radar and the front camera each own an
 * instance writing its own prefs slot; the logic below is identical for both,
 * which is why it lives here rather than twice.
 *
 * WHAT MAKES THIS MORE THAN A LAST-ANSWER SLOT. The stamp is when an answer
 * was FIRST seen, which is why the rendered line reads `since=`. A marginal
 * link stops at one step on one attempt and a different one on the next, so a
 * single slot would restamp on every flip and a link failing the same two ways
 * for a week would always read "a moment ago". Each distinct answer keeps its
 * own first sighting, and [seed] carries them across a process start.
 *
 * The write RATE is deliberately not bounded and the change-debounce does not
 * bound it: a link alternating between two answers differs from the previous
 * one every attempt, so it still writes each time. What it writes is then one
 * of two stable strings rather than a fresh stamp, which is the point. The
 * store's `apply()` is asynchronous, so the cost lands on the writer thread.
 *
 * Thread-safe: the reconnect loops call this from BLE callback threads.
 *
 * @param load read the stored line back, for [seed].
 * @param store persist a rendered line.
 * @param wallClock injectable so the stamping is testable without sleeping.
 */
class LinkProbeRecorder(
    private val load: () -> String?,
    private val store: (String) -> Unit,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile
    private var lastBody: String? = null

    private val lock = Any()

    // Guarded by [lock].
    private val firstSeen = LinkedHashMap<String, Long>()
    private var seeded = false

    /**
     * Record where this attempt stopped, if that differs from last time.
     *
     * @param body the formatted discovered-table plus outcome, from
     *   [LinkProbe.format].
     */
    fun record(body: String) {
        seed()
        if (body == lastBody) return
        lastBody = body
        store(LinkProbe.render(firstSeenMs(body), body))
    }

    /**
     * Read the stored answer back into the debounce, once per process.
     *
     * Without it the first attempt after a restart rewrites an unchanged
     * answer with a fresh stamp, so a link failing the same way for weeks
     * reads `since=` a few seconds ago.
     */
    private fun seed() {
        synchronized(lock) {
            if (seeded) return
            seeded = true
            val stored = LinkProbe.parse(load()) ?: return
            firstSeen[stored.body] = stored.sinceMs
            lastBody = stored.body
        }
    }

    /**
     * First time this exact answer was seen, remembering it if it is new.
     *
     * Bounded: a given device produces a handful of distinct answers, and the
     * cap stops a pathological one growing the map without limit. Eviction is
     * oldest-inserted, which is the one least likely to still be recurring.
     */
    private fun firstSeenMs(body: String): Long = synchronized(lock) {
        firstSeen[body]?.let { return@synchronized it }
        if (firstSeen.size >= MAX_STAMPS) {
            firstSeen.remove(firstSeen.keys.first())
        }
        val now = wallClock()
        firstSeen[body] = now
        now
    }

    companion object {
        /**
         * How many distinct answers keep their own first-seen stamp, per link.
         *
         * Sized above the realistic ceiling because eviction drops the
         * oldest-first-seen entry, so a recurring answer past the cap comes
         * back restamped as now - the exact lie the stamp exists to prevent.
         * One device has one service table, so the answers are the abort
         * tokens plus `handshake-ok`, `no-gatt` and `discovery-failed`: 14 for
         * the radar today, fewer for the camera. This clears both.
         */
        const val MAX_STAMPS = 16
    }
}
