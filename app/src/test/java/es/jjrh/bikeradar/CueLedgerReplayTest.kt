// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end **cue-ledger** golden replay: drives the real
 * [RadarV2Decoder] -> [AlertDecider] -> [AlertCue] pipeline over a few real
 * captured windows and pins the *ordered sequence of audible cues* against a
 * committed golden. Where [CorpusReplayGate] checks per-capture alert
 * *tallies* against a private corpus, this test pins the exact ledger
 * (which cue, what beep count / urgent path, at which relative ms) for a
 * single fixture that ships in the repo - so it runs in CI with no corpus
 * and turns any change to alert parsimony into a reviewable diff.
 *
 * The fixture reaches the stationary and moving urgent paths, the pass
 * clearance and the unconfident wait, and the setting tests below pin what
 * each setting does to it. It holds one example of each, on radar-only wiring
 * with no eBike data at a 20 m alert distance, so it is a regression pin and
 * no substitute for the corpus replay when judging a threshold or a default.
 *
 * Determinism: the loop is driven entirely by each frame's relative
 * timestamp (`frame.relMs`) - the decoder and decider both read that
 * injected clock, never the wall clock - so the ledger is reproducible.
 *
 * Wiring mirrors [OverlayPipeline.fireAlertCue] exactly on the
 * radar-only (no-eBike) path: `bikeSpeedMs` from the radar's
 * device-status field, `bikeNotDriving = null`,
 * `urgentLowSpeedEnabled = true` (the shipped default). [alertMaxM] is
 * pinned to the [data.Prefs] default of 20 m.
 *
 * Regenerating the golden (after an intentional cue change): run
 *   scripts/dev gradle :app:testDebugUnitTest \
 *     --tests es.jjrh.bikeradar.CueLedgerReplayTest \
 *     -Pbikeradar.cueLedgerRecord=true --no-configuration-cache
 * and paste the printed ledgers (between the BEGIN/END markers) into
 * [DEFAULT_GOLDEN] and [ALONG_AXIS_GOLDEN]; the setting tests carry their own
 * literals. Cite the diff in review.
 *
 * Fixture: the same `app/src/test/resources/replay-fixture.txt` used by
 * [RadarV2DecoderReplayTest] / [PipelineReplayTest].
 */
class CueLedgerReplayTest {

    private data class Frame(val relMs: Long, val bytes: ByteArray)

    private fun loadFixture(): List<Frame> {
        val stream = javaClass.classLoader!!.getResourceAsStream("replay-fixture.txt")
            ?: error("replay-fixture.txt missing from test resources")
        return stream.bufferedReader().useLines { lines ->
            lines
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    val parts = line.split(' ', limit = 2)
                    Frame(parts[0].toLong(), parts[1].hexToBytes())
                }
                .toList()
        }
    }

    /**
     * Replays the fixture through decoder + decider and returns the ordered
     * ledger of audible cues (one line per non-silent cue). Beep carries its
     * count; UrgentApproach carries which gate opened it (the v0.11 low-speed
     * moving extension vs the stationary path) since that attribution is the
     * safety-relevant part of the event.
     */
    private fun replayLedger(
        alertMaxM: Int,
        urgentLowSpeedEnabled: Boolean,
        tierDistance: TierDistance = TierDistance.TRUE_RANGE,
        passClearanceM: Float = AlertDecider.DEFAULT_PASS_CLEARANCE_M,
        urgentUnconfidentWaitEnabled: Boolean = false,
    ): List<String> {
        val frames = loadFixture()
        var clock = 0L
        val decoder = RadarV2Decoder(nowMs = { clock })
        val alerts = AlertDecider(tierDistance = tierDistance)
        val ledger = ArrayList<String>()
        for (frame in frames) {
            clock = frame.relMs
            val state = decoder.feed(frame.bytes) ?: continue
            val ev = alerts.decide(
                vehicles = state.vehicles,
                alertMaxM = alertMaxM,
                nowMs = clock,
                bikeSpeedMs = state.bikeSpeedMs,
                bikeNotDriving = null,
                urgentLowSpeedEnabled = urgentLowSpeedEnabled,
                urgentUnconfidentWaitEnabled = urgentUnconfidentWaitEnabled,
                passClearanceM = passClearanceM,
            )
            // Pin that the ledger's notion of "audible" matches production's:
            // describe() returns null exactly when AlertCue.forEvent treats the
            // event as Silence. Today that is only Event.None, but this guards
            // against a future AlertCue change (e.g. a new ducked/silent state)
            // desyncing the recorded ledger from what the rider actually hears.
            val line = describe(ev)
            assertEquals(
                "describe()/AlertCue audibility disagree for $ev",
                AlertCue.forEvent(ev) is AlertCue.Silence,
                line == null,
            )
            line?.let { ledger.add("$clock $it") }
        }
        return ledger
    }

    private fun describe(ev: AlertDecider.Event): String? = when (ev) {
        is AlertDecider.Event.Beep -> "Beep(${ev.count})"
        AlertDecider.Event.Clear -> "Clear"
        is AlertDecider.Event.UrgentApproach ->
            if (ev.viaMovingPath) "Urgent(moving)" else "Urgent(stationary)"
        AlertDecider.Event.None -> null
    }

    private fun recordOrAssert(golden: List<String>, actual: List<String>, label: String) {
        if (System.getProperty("bikeradar.cueLedgerRecord") == "true") {
            println("=== CUE LEDGER $label BEGIN ===")
            actual.forEach { println("        \"$it\",") }
            println("=== CUE LEDGER $label END (${actual.size} cues) ===")
            return
        }
        assertEquals(
            "Cue ledger drifted for $label. If this change is intentional, regenerate " +
                "with -Pbikeradar.cueLedgerRecord=true and cite the diff in review.",
            golden,
            actual,
        )
    }

    @Test
    fun defaultConfigLedgerMatchesGolden() {
        val actual = replayLedger(alertMaxM = 20, urgentLowSpeedEnabled = true)
        recordOrAssert(DEFAULT_GOLDEN, actual, "default(alertMax=20,urgentLowSpeed=on)")
    }

    private fun urgents(ledger: List<String>) = ledger.filter { "Urgent(" in it }

    private fun nonUrgents(ledger: List<String>) = ledger.filterNot { "Urgent(" in it }

    /** The default golden less [cue], which it must hold, so a regenerated
     *  golden that lost the cue cannot turn the comparison into a no-op. */
    private fun defaultWithout(cue: String): List<String> {
        assertTrue("$cue must be in the default ledger", cue in DEFAULT_GOLDEN)
        return DEFAULT_GOLDEN.filter { it != cue }
    }

    @Test
    fun lowSpeedUrgentOffSilencesOnlyTheSlowRidersUrgent() {
        val off = replayLedger(alertMaxM = 20, urgentLowSpeedEnabled = false)
        assertEquals(defaultWithout("86080 Urgent(moving)"), off)
    }

    @Test
    fun unconfidentWaitOnWithholdsOnlyTheSlowingCarsUrgent() {
        val on = replayLedger(alertMaxM = 20, urgentLowSpeedEnabled = true, urgentUnconfidentWaitEnabled = true)
        assertEquals(defaultWithout("111983 Urgent(stationary)"), on)
    }

    @Test
    fun theNarrowestPassClearanceMovesOnlyTheUrgents() {
        val ledger = replayLedger(alertMaxM = 20, urgentLowSpeedEnabled = true, passClearanceM = 0.5f)
        assertEquals(nonUrgents(DEFAULT_GOLDEN), nonUrgents(ledger))
        assertEquals(
            listOf(
                "58117 Urgent(stationary)",
                "62617 Urgent(stationary)",
                "68764 Urgent(stationary)",
                "86080 Urgent(moving)",
                "111983 Urgent(stationary)",
            ),
            urgents(ledger),
        )
    }

    @Test
    fun theWidestPassClearanceMovesOnlyTheUrgents() {
        val ledger = replayLedger(alertMaxM = 20, urgentLowSpeedEnabled = true, passClearanceM = 3.0f)
        assertEquals(nonUrgents(DEFAULT_GOLDEN), nonUrgents(ledger))
        assertEquals(
            listOf(
                "57513 Urgent(stationary)",
                "60446 Urgent(stationary)",
                "63528 Urgent(stationary)",
                "67635 Urgent(stationary)",
                "85903 Urgent(moving)",
                "111983 Urgent(stationary)",
            ),
            urgents(ledger),
        )
    }

    @Test
    fun fixtureDiscriminatesTheTierRule() {
        // The fixture's second window holds a vehicle swinging out to the side
        // while its along-axis distance keeps falling. Scoring tiers on that
        // along-axis distance walks it to the top tier; scoring on true range
        // stops it at the middle one. Recording both ledgers means a silent
        // revert to along-axis tiering fails CI with a readable diff, which the
        // corpus gate cannot do - the corpus is private.
        val alongAxis = replayLedger(
            alertMaxM = 20,
            urgentLowSpeedEnabled = true,
            tierDistance = TierDistance.ALONG_AXIS,
        )
        val trueRange = replayLedger(alertMaxM = 20, urgentLowSpeedEnabled = true)
        assertTrue(
            "fixture no longer separates the two tier rules - it cannot guard the change",
            alongAxis != trueRange,
        )
        recordOrAssert(ALONG_AXIS_GOLDEN, alongAxis, "alongAxis(alertMax=20)")
    }

    @Test
    fun fixtureProducesAudibleCues() {
        // Guard against a future decoder/fixture change that silently empties
        // the pipeline, which would make the golden assertion vacuously pass.
        val actual = replayLedger(alertMaxM = 20, urgentLowSpeedEnabled = true)
        assertTrue("fixture must exercise the alert pipeline", actual.isNotEmpty())
    }

    companion object {
        // Generated by -Pbikeradar.cueLedgerRecord=true. Do not hand-edit;
        // regenerate and cite the diff. A car already in the near third at
        // t=0 (Beep 3) passes the rider at 2064, and the next car, in the far
        // third, is heard there and again at the middle tier as it closes.
        // Two later cars first appear in the far third after a car heard at
        // the middle tier left without passing, so each waits for the middle
        // tier (17906, and 38567 in the off-axis window, where the vehicle
        // stops at the middle tier because its true range never closes).
        // In the urgent windows the stopped rider's fast closers sound three
        // stationary urgents (57660 to 68390), the slow rider's car climbs the
        // tiers into the moving-path urgent at 86080, and the slowing car
        // sounds a stationary urgent at 111983. The Clear at 76539 lands on
        // the slow-rider window's first frame, the stopped window's road
        // having emptied before it ended.
        internal val DEFAULT_GOLDEN: List<String> = listOf(
            "0 Beep(3)",
            "2064 Beep(1)",
            "3862 Beep(2)",
            "17906 Beep(2)",
            "38567 Beep(2)",
            "53386 Beep(2)",
            "57660 Urgent(stationary)",
            "62317 Urgent(stationary)",
            "67072 Clear",
            "68390 Urgent(stationary)",
            "76539 Clear",
            "84099 Beep(1)",
            "84912 Beep(2)",
            "85540 Beep(3)",
            "86080 Urgent(moving)",
            "86531 Beep(3)",
            "89053 Clear",
            "96404 Beep(2)",
            "96661 Beep(3)",
            "100664 Clear",
            "111983 Urgent(stationary)",
        )

        // The same fixture scored on along-axis distance, i.e. the shipped
        // behaviour before true-range tiers. Kept so the difference the change
        // makes is visible in the repo rather than only in a private corpus.
        // The extra cue at 40823 is the point: the vehicle has swung out to
        // the rider's side and its true range is no longer closing, but its
        // along-axis distance keeps falling into the top band. At 96404 the
        // along-axis score also puts a car straight into the top tier that true
        // range starts at the middle one and raises at 96661.
        private val ALONG_AXIS_GOLDEN: List<String> = listOf(
            "0 Beep(3)",
            "2064 Beep(1)",
            "3862 Beep(2)",
            "17906 Beep(2)",
            "38567 Beep(2)",
            "40823 Beep(3)",
            "53386 Beep(2)",
            "57660 Urgent(stationary)",
            "62317 Urgent(stationary)",
            "67072 Clear",
            "68390 Urgent(stationary)",
            "76539 Clear",
            "84099 Beep(1)",
            "84912 Beep(2)",
            "85540 Beep(3)",
            "86080 Urgent(moving)",
            "86531 Beep(3)",
            "89053 Clear",
            "96404 Beep(3)",
            "100664 Clear",
            "111983 Urgent(stationary)",
        )
    }
}
