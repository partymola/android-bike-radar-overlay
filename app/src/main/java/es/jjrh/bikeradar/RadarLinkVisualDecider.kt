// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Decides whether the overlay should show a "reconnecting" state while the
 * rear-radar BLE link is down. Pure function; stateless (no latch, no
 * timestamps owned) so the caller can call it every tick and just reflect the
 * result on screen.
 *
 * Why it exists: when the radar drops mid-ride the per-connection overlay
 * pipeline is torn down (its view detaches), so the screen stops showing rear
 * traffic with no indication that the channel is dead - a frozen/blank overlay
 * reads as "clear road". This decider drives a service-owned banner that says
 * the rear is blind, so silence on the screen stops being ambiguous.
 *
 * NOT eBike-gated, by design and unlike [RadarDropDecider] (whose audio cue is
 * eBike-gated to disambiguate from the walk-away alarm). The visual state is a
 * status indicator, not an alarm: it is cheap (glanceable, not interruptive),
 * it cannot collide with the walk-away alarm, and it is the ONLY dead-radar
 * signal a radar-only rider (no Bosch eBike) ever gets. So it depends only on
 * how long the radar has been down - never on any eBike snapshot. A single
 * uniform [visualThresholdMs] serves every rider: the eBike rider needs the
 * early visual just as much during the 0-60s before their audio cue fires, so
 * there is no rider for whom a later visual is safer.
 *
 * Threshold rationale: reconnects normally resolve in 5-10s (corpus median
 * 8.4s, hard floor 5.3s), so a ~10s threshold rides through normal reconnects
 * and only marks the screen blind once a drop is likely real. The screen
 * auto-clears the instant the radar returns ([radarDownForMs] back to null),
 * so no reconnect-edge latch is needed (that is an audio-parsimony concern).
 */
object RadarLinkVisualDecider {

    enum class LinkVisual { LIVE, RECONNECTING }

    /**
     * @param radarEverLive whether the radar has connected at least once this
     *   session. A cold start (never connected) must stay [LinkVisual.LIVE] -
     *   there was no link to lose, so "reconnecting" would be a lie.
     * @param radarDownForMs how long the radar has been continuously down, or
     *   null when it is currently connected.
     * @param visualThresholdMs how long down before the screen is marked blind.
     * @param paused whether the rider has paused alerts. Paused stays
     *   [LinkVisual.LIVE] so the banner never appears (or is hidden) while the
     *   rider has muted the app - keeping this in the pure decider means the
     *   hide-while-paused branch is unit-tested, not buried in the caller's
     *   ordering.
     */
    fun decide(
        radarEverLive: Boolean,
        radarDownForMs: Long?,
        visualThresholdMs: Long,
        paused: Boolean,
    ): LinkVisual = if (
        !paused &&
        radarEverLive &&
        radarDownForMs != null &&
        radarDownForMs >= visualThresholdMs
    ) {
        LinkVisual.RECONNECTING
    } else {
        LinkVisual.LIVE
    }
}
