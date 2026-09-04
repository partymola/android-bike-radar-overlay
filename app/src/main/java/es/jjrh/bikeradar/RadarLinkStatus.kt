// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Is the app currently working the radar link, having no decoded frames yet?
 *
 * This answers one input to the shared device vocabulary rather than
 * returning a status of its own. It used to be a second three-state enum, so
 * the radar had two vocabularies: the Settings card said "Connected /
 * Connecting / Not in range" while the home row said "Live / Connecting / No
 * signal" about the same radar in the same instant.
 *
 * What is NOT here, deliberately: "connected" means decoded target frames are
 * arriving, which is the question the rider is actually asking, and a battery
 * reading is not evidence of it - the setup sequence publishes one on every
 * attempt that reaches its battery step, so a radar aborting and retrying
 * every 1.5 s keeps a reading permanently fresh while sending no targets at
 * all. Frame freshness is the caller's `fresh` input.
 *
 * A recently-dropped link still counts as connecting for [RECENT_OFF_MS]: an
 * aborting radar cycles connect/abort with ~1.5 s gaps, and without the
 * bridge the card would flap between two states at that cadence.
 *
 * [nowMs] and [offSinceMs] are both elapsedRealtime - the caller must not
 * pass wall clock, which the Settings screens' own tick uses for battery
 * freshness.
 *
 * Scope worth knowing before widening it: [gattActive] is raised once service
 * discovery has succeeded, so an attempt that dies earlier - a null GATT, or
 * discovery itself failing - never reads as connecting and the surface falls
 * through to "No signal". This is intentionally not covered here, because the
 * state has to come from the controller rather than be inferred: covering it
 * means publishing an "attempting" signal at connect time. So a link that
 * fails at the handshake reads Connecting and one that fails earlier does
 * not; which of those a given radar does is what the stored connection probe
 * answers, and it is the thing to read before deciding the earlier signal is
 * worth building.
 */
object RadarLinkStatus {

    /** Longer than the abort loop's quick-reconnect gap (a fixed 1.5 s plus
     *  connect and discovery time - the abort path never grows its backoff),
     *  short enough that a radar genuinely gone reads No signal within two
     *  screen ticks. Deliberately NOT sized to cover an ordinary mid-ride
     *  reconnect (corpus floor ~5.3 s): stretching this window only makes a
     *  switched-off radar lie "Connecting" for longer, and a reconnect inside
     *  it already reads Connecting, which is what is happening. Note the
     *  frame-freshness window does NOT bridge such a gap on its own - the
     *  teardown calls RadarStateBus.clear(), so dataFresh goes false as soon
     *  as the stack notices the drop rather than after RADAR_FRAME_FRESH_MS.
     *  The sibling measurement lives on RADAR_DROP_VISUAL_THRESHOLD_MS. */
    const val RECENT_OFF_MS = 5_000L

    fun isConnecting(
        gattActive: Boolean,
        offSinceMs: Long?,
        nowMs: Long,
    ): Boolean = gattActive || (offSinceMs != null && nowMs - offSinceMs < RECENT_OFF_MS)

    /**
     * Whether to offer the rider the "ride is over" control.
     *
     * The control silences a safety cue, so it is offered only once the radar
     * has been down long enough that the rider has already been shown the
     * disconnected banner. An ordinary mid-ride reconnect runs to a corpus
     * median of 8.4 s, so a gate on "down at all" would put a full-width
     * control that suppresses a warning on screen during routine blips, which
     * is the mis-tap this bounds. [downForMs] is compared against the banner's
     * own threshold so the two surfaces appear together.
     *
     * [radarEverLive] keeps it off a bench session that never rode.
     * [alreadyEnded] stops it being offered twice for one off-episode.
     */
    fun canEndRide(
        radarEverLive: Boolean,
        downForMs: Long?,
        alreadyEnded: Boolean,
        visualThresholdMs: Long,
    ): Boolean = radarEverLive &&
        downForMs != null &&
        downForMs >= visualThresholdMs &&
        !alreadyEnded
}
