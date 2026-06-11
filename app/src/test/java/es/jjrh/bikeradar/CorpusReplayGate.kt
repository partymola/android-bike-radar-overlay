// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

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
 * as written by the in-app capture log and consumed by [ReplayService].
 */
class CorpusReplayGate {

    private val alertMax = 20 // shipped default of Prefs.alertMaxDistanceM

    private data class Tally(
        var beep1: Int = 0,
        var beep2: Int = 0,
        var beep3: Int = 0,
        var urgent: Int = 0,
        var clear: Int = 0,
    ) {
        fun line(name: String) = "$name beep1=$beep1 beep2=$beep2 beep3=$beep3 urgent=$urgent clear=$clear"
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

    private fun replay(log: File): Tally {
        val tally = Tally()
        var ts = 0L
        val decoder = RadarV2Decoder(nowMs = { ts })
        val alerts = AlertDecider()
        log.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val parts = line.split(" ")
            if (parts.size < 3 || parts[1] != "3204") return@forEachLine
            val lineTs = parts[0].toLongOrNull() ?: return@forEachLine
            val bytes = hexToBytes(parts[2]) ?: return@forEachLine
            ts = lineTs
            val state = decoder.feed(bytes) ?: return@forEachLine
            when (
                val ev = alerts.decide(
                    vehicles = state.vehicles,
                    alertMaxM = alertMax,
                    nowMs = lineTs,
                    bikeSpeedMs = state.bikeSpeedMs,
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
        return tally
    }

    @Test
    fun corpusTalliesMatchBaseline() {
        val corpusPath = System.getProperty("bikeradar.corpusDir")
        assumeTrue("no corpus configured (-Pbikeradar.corpusDir) - skipping", corpusPath != null)
        val corpusDir = File(corpusPath!!)
        assumeTrue("corpus dir does not exist: $corpusPath - skipping", corpusDir.isDirectory)

        val captures = corpusDir
            .listFiles { f -> f.name.startsWith("bike-radar-capture-") && f.name.endsWith(".log") }
            ?.sortedBy { it.name } ?: emptyList()
        assumeTrue("corpus dir holds no capture logs - skipping", captures.isNotEmpty())

        // A capture that cannot be replayed (mid-write truncation from a
        // crash, a corrupt byte sequence the decoder rejects with a throw)
        // must name itself in the failure rather than abort the gate for
        // the whole corpus - crash-split tail files are an expected shape.
        val unreplayable = mutableListOf<String>()
        val current = linkedMapOf<String, String>()
        for (capture in captures) {
            runCatching { replay(capture) }
                .onSuccess { current[capture.name] = it.line(capture.name) }
                .onFailure { unreplayable += "UNREPLAYABLE: ${capture.name} ($it)" }
        }

        val baselineFile = File(corpusDir, BASELINE_NAME)
        val record = System.getProperty("bikeradar.corpusRecord") == "true"
        if (record || !baselineFile.exists()) {
            baselineFile.writeText(current.values.joinToString("\n", postfix = "\n"))
            // Surface (don't silently drop) captures that failed to replay:
            // they are absent from the fresh baseline, so every later compare
            // run would fail on them with no record of why.
            unreplayable.forEach { println(it) }
            println(
                "corpus baseline ${if (record) "re-recorded" else "created"}: ${current.size} captures" +
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
            println("note: ${fresh.size} capture(s) not in baseline yet (tolerated): ${fresh.take(5).joinToString()}")
        }

        val problems = changed + missing + unreplayable + corruptBaseline
        if (problems.isNotEmpty()) {
            throw AssertionError(
                "Corpus replay drifted from baseline (${changed.size} changed, ${missing.size} missing, " +
                    "${unreplayable.size} unreplayable, ${corruptBaseline.size} corrupt baseline lines).\n" +
                    "If the alert-behaviour change is intentional, re-record with " +
                    "-Pbikeradar.corpusRecord=true and cite this diff in the review.\n\n" +
                    problems.joinToString("\n"),
            )
        }
        println("corpus replay clean: ${captures.size} captures, ${fresh.size} new since baseline")
    }

    private companion object {
        const val BASELINE_NAME = "corpus-baseline.txt"
    }
}
