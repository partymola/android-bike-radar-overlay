// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end **cue-ledger** golden replay: drives the real
 * [RadarV2Decoder] -> [AlertDecider] -> [AlertCue] pipeline over a 30 s
 * capture and pins the *ordered sequence of audible cues* against a
 * committed golden. Where [CorpusReplayGate] checks per-capture alert
 * *tallies* against a private corpus, this test pins the exact ledger
 * (which cue, what beep count / urgent path, at which relative ms) for a
 * single fixture that ships in the repo - so it runs in CI with no corpus
 * and turns any change to alert parsimony into a reviewable diff.
 *
 * This is the regression pin the batched cue release builds on: every
 * cue-vocabulary or threshold change shifts this ledger, and the
 * before/after diff is the evidence for review. A silent change to
 * cooldown, sustain, the stationary gate, or the imminent-impact override
 * therefore cannot land unnoticed.
 *
 * Determinism: the loop is driven entirely by each frame's relative
 * timestamp (`frame.relMs`) - the decoder and decider both read that
 * injected clock, never the wall clock - so the ledger is reproducible.
 *
 * Wiring mirrors [OverlayPipeline.fireAlertCue] exactly on the
 * radar-only (no-eBike) path: `bikeSpeedMs` from the radar's
 * device-status field, `bikeNotDriving = null`, `climbing = false`,
 * `urgentLowSpeedEnabled = true` (the shipped default). [alertMaxM] is
 * pinned to the [data.Prefs] default of 20 m.
 *
 * Regenerating the golden (after an intentional cue change): run
 *   scripts/dev gradle :app:testDebugUnitTest \
 *     --tests es.jjrh.bikeradar.CueLedgerReplayTest \
 *     -Pbikeradar.cueLedgerRecord=true
 * and paste the printed ledger (between the BEGIN/END markers) into
 * [DEFAULT_GOLDEN]. Cite the diff in review.
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
     * safety-relevant part of the event. Lateral pan position is deliberately
     * excluded - it is experimental and pinned separately by AlertBeeperPanTest;
     * keeping it out of the golden stops sub-metre decoder jitter from churning
     * this safety pin.
     */
    private fun replayLedger(alertMaxM: Int, urgentLowSpeedEnabled: Boolean): List<String> {
        val frames = loadFixture()
        var clock = 0L
        val decoder = RadarV2Decoder(nowMs = { clock })
        val alerts = AlertDecider()
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
                climbing = false,
                urgentLowSpeedEnabled = urgentLowSpeedEnabled,
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

    @Test
    fun urgentLowSpeedToggleIsNoOpForThisFixture() {
        // This 30 s window is moving traffic with no stopped/slow-rider +
        // fast-closer encounter, so it never reaches the imminent-impact
        // override that `urgentLowSpeedEnabled` gates - on and off produce the
        // same ledger. Pinning the equality documents that the single in-repo
        // fixture does not cover the v0.11 low-speed urgent extension (the
        // private CorpusReplayGate corpus does), and flags it if a future
        // decoder change ever makes the toggle bite here - at which point the
        // golden above needs an Urgent entry and a fixture note.
        assertEquals(
            replayLedger(alertMaxM = 20, urgentLowSpeedEnabled = true),
            replayLedger(alertMaxM = 20, urgentLowSpeedEnabled = false),
        )
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
        // regenerate and cite the diff. Four beeps over the 30 s window:
        // a car already in the near third at t=0 (Beep 3), then the closest
        // track's tier stepping out and back as traffic moves through.
        private val DEFAULT_GOLDEN: List<String> = listOf(
            "0 Beep(3)",
            "3862 Beep(2)",
            "14843 Beep(1)",
            "17906 Beep(2)",
        )
    }
}
