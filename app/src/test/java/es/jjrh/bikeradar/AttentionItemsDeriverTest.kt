// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Boundary coverage for [AttentionItemsDeriver]. Every threshold is pinned
 * on both sides (at, just below, just above) so a shifted comparison or a
 * flipped `<=`/`<` fails here - this is a `*Deriver`, held to the tighter
 * branch floor.
 */
class AttentionItemsDeriverTest {

    private fun inputs(
        radarBatteryPct: Int? = null,
        dashcamConfigured: Boolean = false,
        dashcamBatteryPct: Int? = null,
        ebikeSeen: Boolean = false,
        ebikeSoc: Int? = null,
        audioFailureCount: Int = 0,
        uncleanRestart: Boolean = false,
    ) = AttentionItemsDeriver.Inputs(
        radarBatteryPct = radarBatteryPct,
        dashcamConfigured = dashcamConfigured,
        dashcamBatteryPct = dashcamBatteryPct,
        ebikeSeen = ebikeSeen,
        ebikeSoc = ebikeSoc,
        audioFailureCount = audioFailureCount,
        uncleanRestart = uncleanRestart,
    )

    // ── radar battery ────────────────────────────────────────────────────

    @Test fun radarBattery_null_producesNoItem() {
        assertEquals(emptyList<AttentionItem>(), AttentionItemsDeriver.derive(inputs(radarBatteryPct = null)))
    }

    @Test fun radarBattery_atThreshold_fires() {
        assertEquals(
            listOf(AttentionItem(AttentionKind.RADAR_BATTERY, AttentionItemsDeriver.RADAR_CHARGE_PCT)),
            AttentionItemsDeriver.derive(inputs(radarBatteryPct = AttentionItemsDeriver.RADAR_CHARGE_PCT)),
        )
    }

    @Test fun radarBattery_justBelowThreshold_fires() {
        assertEquals(
            listOf(AttentionItem(AttentionKind.RADAR_BATTERY, AttentionItemsDeriver.RADAR_CHARGE_PCT - 1)),
            AttentionItemsDeriver.derive(inputs(radarBatteryPct = AttentionItemsDeriver.RADAR_CHARGE_PCT - 1)),
        )
    }

    @Test fun radarBattery_justAboveThreshold_silent() {
        assertEquals(
            emptyList<AttentionItem>(),
            AttentionItemsDeriver.derive(inputs(radarBatteryPct = AttentionItemsDeriver.RADAR_CHARGE_PCT + 1)),
        )
    }

    // ── dashcam battery (gated on configured) ────────────────────────────

    @Test fun dashcamBattery_notConfigured_neverFires() {
        assertEquals(
            emptyList<AttentionItem>(),
            AttentionItemsDeriver.derive(inputs(dashcamConfigured = false, dashcamBatteryPct = 1)),
        )
    }

    @Test fun dashcamBattery_configuredNullReading_noItem() {
        assertEquals(
            emptyList<AttentionItem>(),
            AttentionItemsDeriver.derive(inputs(dashcamConfigured = true, dashcamBatteryPct = null)),
        )
    }

    @Test fun dashcamBattery_atThreshold_fires() {
        assertEquals(
            listOf(AttentionItem(AttentionKind.DASHCAM_BATTERY, AttentionItemsDeriver.DASHCAM_CHARGE_PCT)),
            AttentionItemsDeriver.derive(
                inputs(dashcamConfigured = true, dashcamBatteryPct = AttentionItemsDeriver.DASHCAM_CHARGE_PCT),
            ),
        )
    }

    @Test fun dashcamBattery_justAboveThreshold_silent() {
        assertEquals(
            emptyList<AttentionItem>(),
            AttentionItemsDeriver.derive(
                inputs(dashcamConfigured = true, dashcamBatteryPct = AttentionItemsDeriver.DASHCAM_CHARGE_PCT + 1),
            ),
        )
    }

    // ── eBike charge (gated on seen) ─────────────────────────────────────

    @Test fun ebike_notSeen_neverFires() {
        assertEquals(
            emptyList<AttentionItem>(),
            AttentionItemsDeriver.derive(inputs(ebikeSeen = false, ebikeSoc = 1)),
        )
    }

    @Test fun ebike_seenNullSoc_noItem() {
        assertEquals(
            emptyList<AttentionItem>(),
            AttentionItemsDeriver.derive(inputs(ebikeSeen = true, ebikeSoc = null)),
        )
    }

    @Test fun ebike_atThreshold_fires() {
        assertEquals(
            listOf(AttentionItem(AttentionKind.EBIKE_BATTERY, AttentionItemsDeriver.EBIKE_CHARGE_PCT)),
            AttentionItemsDeriver.derive(inputs(ebikeSeen = true, ebikeSoc = AttentionItemsDeriver.EBIKE_CHARGE_PCT)),
        )
    }

    @Test fun ebike_justAboveThreshold_silent() {
        assertEquals(
            emptyList<AttentionItem>(),
            AttentionItemsDeriver.derive(inputs(ebikeSeen = true, ebikeSoc = AttentionItemsDeriver.EBIKE_CHARGE_PCT + 1)),
        )
    }

    // ── audio failures ───────────────────────────────────────────────────

    @Test fun audioFailures_zero_silent() {
        assertEquals(emptyList<AttentionItem>(), AttentionItemsDeriver.derive(inputs(audioFailureCount = 0)))
    }

    @Test fun audioFailures_one_fires() {
        assertEquals(
            listOf(AttentionItem(AttentionKind.AUDIO_FAILURES, 1)),
            AttentionItemsDeriver.derive(inputs(audioFailureCount = 1)),
        )
    }

    // ── unclean restart ──────────────────────────────────────────────────

    @Test fun uncleanRestart_true_fires() {
        assertEquals(
            listOf(AttentionItem(AttentionKind.UNCLEAN_RESTART)),
            AttentionItemsDeriver.derive(inputs(uncleanRestart = true)),
        )
    }

    @Test fun uncleanRestart_false_silent() {
        assertEquals(emptyList<AttentionItem>(), AttentionItemsDeriver.derive(inputs(uncleanRestart = false)))
    }

    // ── ordering ─────────────────────────────────────────────────────────

    @Test fun allItems_areInFixedOrder() {
        val items = AttentionItemsDeriver.derive(
            inputs(
                radarBatteryPct = 5,
                dashcamConfigured = true,
                dashcamBatteryPct = 5,
                ebikeSeen = true,
                ebikeSoc = 5,
                audioFailureCount = 2,
                uncleanRestart = true,
            ),
        )
        assertEquals(
            listOf(
                AttentionKind.RADAR_BATTERY,
                AttentionKind.DASHCAM_BATTERY,
                AttentionKind.EBIKE_BATTERY,
                AttentionKind.AUDIO_FAILURES,
                AttentionKind.UNCLEAN_RESTART,
            ),
            items.map { it.kind },
        )
    }

    // ── live-clear (home card) ───────────────────────────────────────────

    private val fullFeed = listOf(
        AttentionItem(AttentionKind.RADAR_BATTERY, 10),
        AttentionItem(AttentionKind.DASHCAM_BATTERY, 10),
        AttentionItem(AttentionKind.EBIKE_BATTERY, 10),
        AttentionItem(AttentionKind.AUDIO_FAILURES, 3),
        AttentionItem(AttentionKind.UNCLEAN_RESTART),
    )

    @Test fun filter_nullLive_keepsEverything() {
        assertEquals(
            fullFeed,
            AttentionItemsDeriver.filterUnresolved(fullFeed, AttentionItemsDeriver.LiveState(null, null, null)),
        )
    }

    @Test fun filter_freshReadingAtThreshold_doesNotClear() {
        // At the threshold the device is still low (resolved is strictly above).
        val live = AttentionItemsDeriver.LiveState(
            radarBatteryPct = AttentionItemsDeriver.RADAR_CHARGE_PCT,
            dashcamBatteryPct = null,
            ebikeSoc = null,
        )
        assertEquals(fullFeed, AttentionItemsDeriver.filterUnresolved(fullFeed, live))
    }

    @Test fun filter_freshReadingAboveThreshold_clearsThatBattery() {
        val live = AttentionItemsDeriver.LiveState(
            radarBatteryPct = AttentionItemsDeriver.RADAR_CHARGE_PCT + 1,
            dashcamBatteryPct = AttentionItemsDeriver.DASHCAM_CHARGE_PCT + 1,
            ebikeSoc = AttentionItemsDeriver.EBIKE_CHARGE_PCT + 1,
        )
        assertEquals(
            listOf(
                AttentionItem(AttentionKind.AUDIO_FAILURES, 3),
                AttentionItem(AttentionKind.UNCLEAN_RESTART),
            ),
            AttentionItemsDeriver.filterUnresolved(fullFeed, live),
        )
    }

    @Test fun filter_historicalItems_neverCleared() {
        val historical = listOf(
            AttentionItem(AttentionKind.AUDIO_FAILURES, 3),
            AttentionItem(AttentionKind.UNCLEAN_RESTART),
        )
        val live = AttentionItemsDeriver.LiveState(100, 100, 100)
        assertEquals(historical, AttentionItemsDeriver.filterUnresolved(historical, live))
    }
}
