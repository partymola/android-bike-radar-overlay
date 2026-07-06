// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * The end-of-trip "needs attention" items - bucket 3 in the rider's cue
 * taxonomy (things that can wait, surfaced once the ride is over, never
 * in-ride). Delivered on the post-ride notification and the home-screen
 * attention card.
 *
 * A [kind] plus an optional numeric [value] (a battery percentage, or a
 * failure count); the user-facing wording is resolved at each surface so
 * this stays Android-free and JVM-testable, exactly as [ui.MainStatusModel]
 * keeps its copy out of the pure logic.
 */
enum class AttentionKind {
    RADAR_BATTERY,
    DASHCAM_BATTERY,
    EBIKE_BATTERY,
    AUDIO_FAILURES,
    UNCLEAN_RESTART,
}

data class AttentionItem(val kind: AttentionKind, val value: Int? = null)

/**
 * Pure derivation of the needs-attention feed from the state captured at
 * ride end, plus the display-time live-clear used by the home card.
 *
 * The battery thresholds are deliberately more generous than the (now
 * removed) in-ride critical level: a radar the rider can still use but
 * should top up tonight is worth one calm end-of-trip nudge, not an in-ride
 * alarm. All thresholds are single constants - tune with the user.
 */
object AttentionItemsDeriver {

    /** Radar battery at/below this at ride end -> "charge tonight". More
     *  generous than the old in-ride critical level (10%) on purpose: this
     *  is a calm end-of-trip nudge, not a mid-ride alarm. Tune with the user. */
    const val RADAR_CHARGE_PCT = 25

    /** Dashcam battery at/below this at ride end -> "charge". Tune with the user. */
    const val DASHCAM_CHARGE_PCT = 25

    /** eBike state-of-charge at/below this at ride end -> "charge". Tune with
     *  the user. */
    const val EBIKE_CHARGE_PCT = 20

    /**
     * State captured at ride end. Nullable battery fields mean "no reading
     * this ride" (device absent / never seen) and produce no item. The
     * `*Configured` / `*Seen` flags gate optional accessories so a rider
     * without a dashcam or eBike never sees their items.
     */
    data class Inputs(
        val radarBatteryPct: Int?,
        val dashcamConfigured: Boolean,
        val dashcamBatteryPct: Int?,
        val ebikeSeen: Boolean,
        val ebikeSoc: Int?,
        val audioFailureCount: Int,
        val uncleanRestart: Boolean,
    )

    /** Live readings the home card re-checks a persisted item against. A
     *  battery item clears only on a FRESH reading at/above its threshold;
     *  an absent (stale / null) reading leaves the item standing. */
    data class LiveState(
        val radarBatteryPct: Int?,
        val dashcamBatteryPct: Int?,
        val ebikeSoc: Int?,
    )

    /** Build the feed. Order is fixed (safety-adjacent radar first, then the
     *  other batteries, then the diagnostics) so the notification and card
     *  read the same top-down. */
    fun derive(inputs: Inputs): List<AttentionItem> = buildList {
        if (inputs.radarBatteryPct != null && inputs.radarBatteryPct <= RADAR_CHARGE_PCT) {
            add(AttentionItem(AttentionKind.RADAR_BATTERY, inputs.radarBatteryPct))
        }
        if (inputs.dashcamConfigured &&
            inputs.dashcamBatteryPct != null &&
            inputs.dashcamBatteryPct <= DASHCAM_CHARGE_PCT
        ) {
            add(AttentionItem(AttentionKind.DASHCAM_BATTERY, inputs.dashcamBatteryPct))
        }
        if (inputs.ebikeSeen &&
            inputs.ebikeSoc != null &&
            inputs.ebikeSoc <= EBIKE_CHARGE_PCT
        ) {
            add(AttentionItem(AttentionKind.EBIKE_BATTERY, inputs.ebikeSoc))
        }
        if (inputs.audioFailureCount > 0) {
            add(AttentionItem(AttentionKind.AUDIO_FAILURES, inputs.audioFailureCount))
        }
        if (inputs.uncleanRestart) {
            add(AttentionItem(AttentionKind.UNCLEAN_RESTART))
        }
    }

    /**
     * Display-time filter for the home card: drop any persisted item whose
     * condition has since resolved. Only the battery items have a live
     * signal - a battery item clears when a fresh reading is at/above its
     * threshold. The historical items ([AttentionKind.AUDIO_FAILURES],
     * [AttentionKind.UNCLEAN_RESTART]) have no live signal, so they stand
     * until the next ride re-derives the set - or the rider dismisses them
     * from the card (`AttentionStore.remove`).
     */
    fun filterUnresolved(persisted: List<AttentionItem>, live: LiveState): List<AttentionItem> = persisted.filter { item ->
        when (item.kind) {
            AttentionKind.RADAR_BATTERY -> !resolved(live.radarBatteryPct, RADAR_CHARGE_PCT)
            AttentionKind.DASHCAM_BATTERY -> !resolved(live.dashcamBatteryPct, DASHCAM_CHARGE_PCT)
            AttentionKind.EBIKE_BATTERY -> !resolved(live.ebikeSoc, EBIKE_CHARGE_PCT)
            AttentionKind.AUDIO_FAILURES, AttentionKind.UNCLEAN_RESTART -> true
        }
    }

    /** A fresh reading strictly above the charge threshold means the rider
     *  topped it up - the item is resolved. A null (no fresh reading) or a
     *  still-low reading leaves it standing. */
    private fun resolved(livePct: Int?, thresholdPct: Int): Boolean = livePct != null && livePct > thresholdPct
}
