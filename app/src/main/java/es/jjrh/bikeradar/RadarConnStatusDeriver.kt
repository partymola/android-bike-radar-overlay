// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/** What the Settings radar card should say about the link right now. */
enum class RadarConnStatus { CONNECTED, CONNECTING, NOT_IN_RANGE }

/**
 * Pure tri-state for the Settings radar card. "Connected" means decoded target
 * frames are arriving, which is the question the rider is actually asking; a
 * battery reading is not evidence of it, because the setup sequence publishes
 * one on every attempt that reaches its battery step, so a radar aborting and
 * retrying every 1.5 s keeps a reading permanently fresh while sending no
 * targets at all. The old screen collapsed everything short of that to "Not in
 * range", which is false for a radar the app is actively connecting to and
 * failing the setup sequence against. That rider needs to hear "Connecting",
 * or the report we get is "never in range" about a radar the app talks to
 * every couple of seconds.
 *
 * A recently-dropped link still reads CONNECTING for [RECENT_OFF_MS]: an
 * aborting radar cycles connect/abort with ~1.5 s gaps, and without the bridge
 * the card would flap between two states at that cadence.
 *
 * [nowMs] and [offSinceMs] are both elapsedRealtime - the caller must not pass
 * wall clock, which the Settings screen's own tick uses for battery freshness.
 *
 * Scope worth knowing before widening it: [gattActive] is raised once service
 * discovery has succeeded, so an attempt that dies earlier - a null GATT, or
 * discovery itself failing - never reaches CONNECTING and the card still reads
 * NOT_IN_RANGE. This is intentionally not covered here, because the state has
 * to come from the controller rather than be inferred: covering it means
 * publishing an "attempting" signal at connect time. So a link that fails at
 * the handshake reads CONNECTING and one that fails earlier does not; which of
 * those a given radar does is what the stored connection probe answers, and it
 * is the thing to read before deciding the earlier signal is worth building.
 */
object RadarConnStatusDeriver {

    /** Longer than the abort loop's quick-reconnect gap (a fixed 1.5 s plus
     *  connect and discovery time - the abort path never grows its backoff),
     *  short enough that a radar genuinely gone reads NOT_IN_RANGE within two
     *  screen ticks. Deliberately NOT sized to cover an ordinary mid-ride
     *  reconnect (corpus floor ~5.3 s): stretching this window only makes a
     *  switched-off radar lie "Connecting" for longer, and a reconnect inside
     *  it already reads CONNECTING, which is what is happening. Note the
     *  frame-freshness window does NOT bridge such a gap on its own - the
     *  teardown calls RadarStateBus.clear(), so dataFresh goes false as soon
     *  as the stack notices the drop rather than after RADAR_FRAME_FRESH_MS.
     *  The sibling measurement lives on RADAR_DROP_VISUAL_THRESHOLD_MS. */
    const val RECENT_OFF_MS = 5_000L

    fun derive(
        dataFresh: Boolean,
        gattActive: Boolean,
        offSinceMs: Long?,
        nowMs: Long,
    ): RadarConnStatus = when {
        dataFresh -> RadarConnStatus.CONNECTED
        gattActive -> RadarConnStatus.CONNECTING
        offSinceMs != null && nowMs - offSinceMs < RECENT_OFF_MS -> RadarConnStatus.CONNECTING
        else -> RadarConnStatus.NOT_IN_RANGE
    }
}
