// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Replays a private ride-capture corpus through the real [RadarV2Decoder] +
 * [AlertDecider] (production defaults) and compares per-capture alert-event
 * tallies against a recorded baseline. This is the repo's "don't change the
 * alert behaviour without re-running the capture replay" policy as a
 * one-command gate.
 *
 * Opt-in by construction: the corpus is the maintainer's own ride history and
 * never ships with the repo, so the gate runs only when pointed at one:
 *
 * ```
 * scripts/dev gradle :app:testDebugUnitTest \
 *   --tests es.jjrh.bikeradar.CorpusReplayGate \
 *   -Pbikeradar.corpusDir=<your capture directory>
 * ```
 *
 * Without `bikeradar.corpusDir` the test assume-skips, so CI and contributors
 * without a corpus see a skipped test, not a failure. The baseline
 * (`corpus-baseline.txt`) lives INSIDE the corpus directory - it is derived
 * from private ride data and stays with it.
 *
 * Comparison rules are growth-tolerant, because the corpus gains files with
 * every ride:
 *  - a capture present in both baseline and corpus must tally EXACTLY;
 *  - a capture in the corpus but not the baseline is reported and tolerated
 *    (re-record at the next deliberate baseline update);
 *  - a capture in the baseline but missing from the corpus FAILS - a
 *    shrinking corpus silently weakens every future replay decision.
 *
 * On an intentional alert-behaviour change, re-record with
 * `-Pbikeradar.corpusRecord=true` and use the failure diff as the
 * before/after evidence for the change's review.
 *
 * Capture format: lines of `<epoch-ms> 3204 <hex>` (the V2 notify stream),
 * as written by the in-app capture log and consumed by [ReplayService], plus
 * the `ebike` lines [EBikeCaptureFormatter] writes, which carry the rider
 * speed the urgent gates are conditioned on.
 */
class CorpusReplayGate {

    /**
     * Alert envelopes every capture is replayed at, each tallied separately.
     *
     * One envelope is not enough, and which one is not a detail: both urgent
     * gates are bounded by `alertMaxM`, so a cue that fires for a rider at 30 m
     * is out of reach at 20 and the replay reports a confident zero. A reported
     * false urgent cue fired at 28 m and was invisible to this gate for exactly
     * that reason.
     *
     * 20 is the shipped default and 30 is the maintainer's own setting. The
     * Settings slider reaches 40, so this pair is not the full range: a cue a
     * rider at 40 m would hear is still out of reach here.
     */
    private val alertEnvelopesM = listOf(20, 30)

    private data class Tally(
        var beep1: Int = 0,
        var beep2: Int = 0,
        var beep3: Int = 0,
        var urgent: Int = 0,
        var clear: Int = 0,
        // Not outcomes: what the parsers actually found. A renamed capture
        // field would otherwise degrade every input to "absent" and leave the
        // gate green forever, which is the one failure this harness must not
        // have. Counted here, a format change surfaces as CHANGED everywhere.
        //
        // Read the scope exactly, because these cover less than they look
        // like they do. `ebikeLines` counts the LINE PREFIX, so renaming the
        // prefix is caught while renaming `spd_raw` or `notdrv` inside it is
        // not: the count holds and every speed silently falls back to the
        // radar's own field. The climb parser has no counter at all. Both are
        // fixable only at a deliberate baseline re-record, since each counter
        // is part of the compared line and adding one invalidates every
        // existing entry.
        var ebikeLines: Int = 0,
        var turnLines: Int = 0,
        var offsetCm: Int = 0,
    ) {
        fun line(key: String) = "$key beep1=$beep1 beep2=$beep2 beep3=$beep3 urgent=$urgent clear=$clear " +
            "ebike=$ebikeLines turns=$turnLines offset=$offsetCm"
    }

    private fun hexToBytes(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        return try {
            ByteArray(s.length / 2) {
                ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte()
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Latest `ebike` line seen, as the fields the live call site reads. */
    private data class EbikeState(val speedRaw: Int?, val notDriving: Boolean?)

    /** `ebike spd_raw=N ... notdrv=0|1`, written by [EBikeCaptureFormatter].
     *  Fields never observed are omitted from the line, so each is nullable. */
    private fun parseEbike(line: String): EbikeState {
        var speedRaw: Int? = null
        var notDriving: Boolean? = null
        for (field in line.removePrefix("ebike ").split(" ")) {
            val (k, v) = field.split("=", limit = 2).takeIf { it.size == 2 } ?: continue
            when (k) {
                "spd_raw" -> speedRaw = v.toIntOrNull()
                "notdrv" -> notDriving = v == "1"
            }
        }
        return EbikeState(speedRaw, notDriving)
    }

    /**
     * Read a capture whether or not it has been archived.
     *
     * Captures are gzipped in place once they age, so a `.log`-only reader
     * stops seeing the corpus's most recent rides - the ones a change is most
     * likely to be about. Measured on the maintainer's corpus: an eighth of it
     * existed only as `.gz`, covering every recent ride.
     */
    private fun <T> withLines(log: File, block: (Sequence<String>) -> T): T = if (CaptureLogFiles.isGzipped(log)) {
        // `use` on the FileInputStream, not just the reader: GZIPInputStream
        // reads the header eagerly, so a corrupt archive throws with the file
        // already open and `useLines` never reached.
        log.inputStream().use { raw -> GZIPInputStream(raw).bufferedReader().useLines(block) }
    } else {
        log.bufferedReader().useLines(block)
    }

    /**
     * The rider's mount offset, from the capture's own `# radar_fw` header.
     *
     * Every `rangeXm` the decoder emits is shifted by it, and `rangeXm` is what
     * the off-axis veto, the predicted-pass fit and the true-range tiering all
     * read - so replaying at the default 0 judges a different geometry from the
     * one the rider rode. Absent header (older captures) means no offset was
     * recorded, which is what 0 says.
     */
    private fun lateralOffsetCm(log: File): Int = withLines(log) { lines ->
        lines.firstNotNullOfOrNull { line ->
            if (!line.startsWith("# radar_fw")) {
                null
            } else {
                Regex("lateral_offset_cm=(-?\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            }
        } ?: 0
    }

    private fun turnStateOf(line: String): TurnStateDecider.State? = when {
        line.startsWith("# turn state=TURNING") -> TurnStateDecider.State.TURNING
        line.startsWith("# turn state=HOLD") -> TurnStateDecider.State.HOLD
        line.startsWith("# turn state=IDLE") -> TurnStateDecider.State.IDLE
        else -> null
    }

    private fun replay(log: File, alertMax: Int, offsetCm: Int): Tally {
        val tally = Tally(offsetCm = offsetCm)
        var ts = 0L
        val decoder = RadarV2Decoder(nowMs = { ts }, lateralOffsetCm = offsetCm)
        val alerts = AlertDecider()
        // Turn state as the app itself decided it, read back from the
        // transitions TurnSensorController wrote. Forced to IDLE the
        // clear-deferral never runs at all, so every `clear=` tally was a
        // number from a code path the rider does not have. Re-deriving it from
        // the raw `# turn yaw` trace would be a second implementation to keep
        // in step; the logged verdict is the decision that was actually made.
        var turnState = TurnStateDecider.State.IDLE
        // Climb override as the app decided it, same argument as turn state.
        // Re-deriving it here needed a monotonic clock the capture does not
        // carry, and the obvious substitute manufactures a climb: rider power
        // above the threshold on an `ebike` line before the first radar frame
        // starts the run at zero, and the next line is then thirty seconds
        // past it. No capture in the corpus carries this line yet, so the
        // input is inert today and correct when one does.
        var climbing = false
        // The rider-speed signal the URGENT path is gated on. The gate always
        // read the radar's own device-status field, which about half the
        // corpus carries; what it never read was the bonded eBike's wheel
        // speed, which is the only source on the rest. On those rides every
        // urgent gate was shut and the replay answered `urgent=0` however the
        // decider behaved, which is a confident wrong answer rather than a
        // missing one. Feeding both moved the corpus from 65 urgent cues to
        // 55 and changed 34 of 176 captures.
        // Sourced the way `OverlayPipeline.fireAlertCue` does: the eBike's
        // wheel speed wins outright, the radar's device-status field is the
        // fallback. `ebike` lines carry no timestamp of their own, so the
        // newest one seen in file order is the snapshot in force - the same
        // interleaving the live service saw when it wrote them.
        var ebike: EbikeState? = null
        var ebikeLines = 0
        var turnLines = 0
        withLines(log) { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("ebike ")) {
                    ebike = parseEbike(line)
                    ebikeLines++
                    return@forEach
                }
                if (line.startsWith(CLIMB_PREFIX)) {
                    climbing = line.removePrefix(CLIMB_PREFIX).startsWith("true")
                    return@forEach
                }
                turnStateOf(line)?.let {
                    turnState = it
                    turnLines++
                    return@forEach
                }
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val parts = line.split(" ")
                if (parts.size < 3 || parts[1] != "3204") return@forEach
                val lineTs = parts[0].toLongOrNull() ?: return@forEach
                val bytes = hexToBytes(parts[2]) ?: return@forEach
                ts = lineTs
                val state = decoder.feed(bytes) ?: return@forEach
                when (
                    val ev = alerts.decide(
                        vehicles = state.vehicles,
                        alertMaxM = alertMax,
                        nowMs = lineTs,
                        bikeSpeedMs = ebike?.speedRaw?.let { it / 360f } ?: state.bikeSpeedMs,
                        bikeNotDriving = ebike?.notDriving,
                        climbing = climbing,
                        turnState = turnState,
                    )
                ) {
                    is AlertDecider.Event.Beep -> when (ev.count) {
                        1 -> tally.beep1++
                        2 -> tally.beep2++
                        else -> tally.beep3++
                    }
                    AlertDecider.Event.Clear -> tally.clear++
                    is AlertDecider.Event.UrgentApproach -> tally.urgent++
                    AlertDecider.Event.None -> {}
                }
            }
        }
        tally.ebikeLines = ebikeLines
        tally.turnLines = turnLines
        return tally
    }

    @Test
    fun corpusTalliesMatchBaseline() {
        val corpusPath = System.getProperty("bikeradar.corpusDir")
        assumeTrue("no corpus configured (-Pbikeradar.corpusDir) - skipping", corpusPath != null)
        val corpusDir = File(corpusPath!!)
        assumeTrue("corpus dir does not exist: $corpusPath - skipping", corpusDir.isDirectory)

        // Keyed on the STEM, not the file name, and the uncompressed copy wins
        // where both exist. Archiving a capture must not read as one capture
        // vanishing and a different one appearing, which is what a name key
        // would do to every baseline line the moment a ride is gzipped.
        val captures = corpusDir
            .listFiles { f -> CaptureLogFiles.isCaptureLog(f) }
            .orEmpty()
            .groupBy { it.name.removeSuffix(".gz") }
            .mapValues { (_, files) -> files.firstOrNull { !CaptureLogFiles.isGzipped(it) } ?: files.first() }
            .toSortedMap()
            .values
            .toList()
        assumeTrue("corpus dir holds no capture logs - skipping", captures.isNotEmpty())

        // A capture that cannot be replayed (mid-write truncation from a
        // crash, a corrupt byte sequence the decoder rejects with a throw)
        // must name itself in the failure rather than abort the gate for
        // the whole corpus - crash-split tail files are an expected shape.
        // key -> why. Keyed so a capture that never replayed can be told from
        // one that used to: the first is a corpus fact to report, the second a
        // regression to fail on. Without the split, one truncated archive
        // fails every compare run forever and re-recording cannot clear it,
        // because record mode leaves unreplayable captures out of the baseline.
        val unreplayable = linkedMapOf<String, String>()
        val current = linkedMapOf<String, String>()
        for (capture in captures) {
            // A per-capture fact, so read once rather than once per envelope.
            val offsetCm = runCatching { lateralOffsetCm(capture) }.getOrNull()
            if (offsetCm == null) {
                unreplayable[capture.name] = "UNREPLAYABLE: ${capture.name} (unreadable)"
                continue
            }
            for (envelopeM in alertEnvelopesM) {
                val key = "${capture.name.removeSuffix(".gz")}@$envelopeM"
                runCatching { replay(capture, envelopeM, offsetCm) }
                    .onSuccess { current[key] = it.line(key) }
                    .onFailure { unreplayable[key] = "UNREPLAYABLE: $key ($it)" }
            }
        }

        val baselineFile = File(corpusDir, BASELINE_NAME)
        val record = System.getProperty("bikeradar.corpusRecord") == "true"
        if (record || !baselineFile.exists()) {
            baselineFile.writeText(current.values.joinToString("\n", postfix = "\n"))
            // Surface (don't silently drop) captures that failed to replay:
            // they are absent from the fresh baseline, so every later compare
            // run would fail on them with no record of why.
            unreplayable.values.forEach { println(it) }
            println(
                "corpus baseline ${if (record) "re-recorded" else "created"}: ${current.size} replays " +
                    "(${captures.size} captures x ${alertEnvelopesM.size} envelopes)" +
                    if (unreplayable.isNotEmpty()) " (${unreplayable.size} unreplayable, NOT in baseline)" else "",
            )
            return
        }

        // A baseline line without a separator is corruption (hand edit,
        // partial write); call it what it is instead of letting it surface
        // as a misleading "missing from corpus" failure.
        val (parseable, corrupt) = baselineFile.readLines()
            .filter { it.isNotBlank() }
            .partition { it.contains(' ') }
        val corruptBaseline = corrupt.map { "CORRUPT baseline line: $it" }
        val baseline = parseable.associateBy { it.substringBefore(' ') }

        val changed = baseline.keys.intersect(current.keys)
            .filter { baseline[it] != current[it] }
            .map { "CHANGED:\n  baseline ${baseline[it]}\n  now      ${current[it]}" }
        val missing = (baseline.keys - current.keys).map { "MISSING from corpus: $it" }
        val fresh = (current.keys - baseline.keys)
        if (fresh.isNotEmpty()) {
            println("note: ${fresh.size} replay(s) not in baseline yet (tolerated): ${fresh.take(5).joinToString()}")
        }

        // Only a capture the baseline knows is a regression; a corrupt archive
        // that never replayed is reported and tolerated, like a fresh key.
        val (regressed, tolerated) = unreplayable.entries.partition { it.key in baseline }
        tolerated.forEach { println(it.value + " (never in baseline, tolerated)") }
        val problems = changed + missing + regressed.map { it.value } + corruptBaseline
        if (problems.isNotEmpty()) {
            throw AssertionError(
                "Corpus replay drifted from baseline (${changed.size} changed, ${missing.size} missing, " +
                    "${regressed.size} unreplayable, ${corruptBaseline.size} corrupt baseline lines).\n" +
                    "If the alert-behaviour change is intentional, re-record with " +
                    "-Pbikeradar.corpusRecord=true and cite this diff in the review.\n\n" +
                    problems.joinToString("\n"),
            )
        }
        println("corpus replay clean: ${captures.size} captures x ${alertEnvelopesM.size} envelopes, ${fresh.size} new since baseline")
    }

    private companion object {
        const val BASELINE_NAME = "corpus-baseline.txt"

        /** What `EBikeSnapshotCoordinator` writes when the climb verdict flips. */
        const val CLIMB_PREFIX = "# ebike climbing="
    }
}
