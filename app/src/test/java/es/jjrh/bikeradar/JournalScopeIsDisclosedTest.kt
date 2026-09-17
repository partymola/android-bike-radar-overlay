// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Forces an author adding a line to the link journal to say what kind of line
 * it is, and fails if a rider-facing one lacks its words in either locale.
 *
 * Every file that reaches the journal is scanned, and [everyFileThatReachesTheJournalIsListed]
 * is what makes "every" true, in the only sense a source scan can: a file that
 * constructs the journal, names the service's field, or takes a `journal` or
 * `log` lambda has to be in [writers], or be one of the two readers. A class
 * handed a writer's lambda under some other name is the one shape it cannot
 * see. Each literal a writer hands the journal is then one of two things. A
 * machinery line, named in [machinery], which the Privacy copy covers as a
 * class: what the app's own machinery did, "plus app events of the same
 * kind", a phrase [theEnglishDisclosureCarriesTheMachineryClass] pins.
 * Or a rider-facing event, named in [declared] with the phrase each locale's
 * disclosure must carry for it. A literal in neither fails.
 *
 * What stays with the author is the classification itself: an event written
 * into [machinery] passes, and a call whose literal has a value appended is
 * judged on the literal alone. That is why the Privacy copy is written as a
 * class with examples rather than a closed list. An instruction in a KDoc to
 * keep the disclosure in step is not a mechanism, because a commit can break
 * it silently; the two lists are the mechanism, and the classification is the
 * one decision they leave to a reader.
 */
@RunWith(RobolectricTestRunner::class)
class JournalScopeIsDisclosedTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /**
     * A file that writes to the journal, the call shape it uses, and the calls
     * that forward another writer's line rather than write one of their own,
     * each mapped to the writer it feeds. A forwarder carries no literal, so
     * it is excluded from the literal count by its exact text, and it may
     * appear once: a second class wired the same way is a new writer.
     */
    private class Writer(val file: String, call: String, val forwarders: Map<String, String> = emptyMap()) {
        val calls = Regex(call)
        val literal = Regex(call + """\s*"([^"]*)"""")
    }

    private val writers = listOf(
        Writer("RadarLinkCoordinator.kt", """journal\("""),
        Writer("RadarLinkController.kt", """journal\("""),
        Writer("CameraLightLinkController.kt", """journal\("""),
        Writer("RideCheckpoint.kt", """journal\("""),
        Writer(
            "BikeRadarService.kt",
            """linkJournal\.log\(""",
            forwarders = mapOf("linkJournal.log(it)" to "RideCheckpoint.kt", "linkJournal.log(m)" to "EBikeStatusReader.kt"),
        ),
        Writer("BatteryScanReceiver.kt", """\.log\("""),
        Writer("EBikeStatusReader.kt", """(?<![\w.])log\("""),
    )

    /** Files that open the journal to read it and never write. */
    private val readers = setOf("LinkEventJournal.kt", "DebugScreen.kt")

    /** Writers the service hands its journal to as a method reference. */
    private val handedByReference = setOf("RadarLinkCoordinator.kt", "RadarLinkController.kt", "CameraLightLinkController.kt")

    /**
     * Each rider-facing event, with the words each locale's connection-log
     * disclosure has to carry for it.
     *
     * The phrases live HERE rather than in the assertions, so adding an event
     * cannot be cleared by adding a line to a list: the row demands a phrase,
     * and the phrase is then looked for in both locales. Several events
     * legitimately share one phrase.
     */
    private data class Event(val literal: String, val en: String, val es: String)

    private val declared = listOf(
        Event("dead-radar alert sounded (cue ${'$'}{decision.cueCount})", "drop alert", "aviso de desconexión"),
        Event("ride ended by rider", "ride is over", "ruta ha terminado"),
        Event("walk-away alarm snoozed by rider", "walk-away alarm", "alarma al alejarte"),
        Event("walk-away alarm dismissed by rider", "walk-away alarm", "alarma al alejarte"),
        Event("walk-away snooze over, alarm re-armed", "walk-away alarm", "alarma al alejarte"),
    )

    /**
     * Lines recording what the app's own machinery did: a link transition,
     * a service start or stop, a recovery, a rebuild. No rider action and no
     * cue the rider heard, which is what separates them from [declared].
     * Exact text rather than a pattern, so a new line has to be added here by
     * hand by someone deciding it is machinery and not an event.
     */
    private val machinery = setOf(
        "radar bond removed; reconnect loop stopped",
        "radar link start refused: bond lost",
        "radar link start \$name",
        "radar re-paired (\$via) \$name",
        "radar reconnect in \${delayMs}ms\$tag",
        "radar conn state status=\$status newState=\$newState",
        "radar connectGatt returned null",
        "radar services discovery failed",
        "radar connected, running handshake",
        "radar legacy candidate; cache refresh=true",
        "radar legacy fallback unavailable (cache refresh failed)",
        "radar legacy stream attempt after \$handshakeAbort",
        "radar handshake aborted at \$handshakeAbort (quick reconnect)",
        "radar handshake complete",
        "radar V2 stream silent \${ageMs}ms; tearing down",
        "radar legacy stream subscribe ok=\$subscribed",
        "radar legacy stream silent; tearing down",
        "radar legacy stream live",
        "radar legacy stream ended \$tally",
        "camera link start \$name",
        "camera conn state status=\$status newState=\$newState",
        "camera connectGatt returned null",
        "camera services discovery failed",
        "camera handshake failed at \$handshakeAbort (quick reconnect)",
        "camera handshake complete",
        "recovered a ride from the crash checkpoint (partial=\${leftover.partial})",
        "service started (unclean restarts=\${prefs.dirtyRestartCount})",
        "audio tracks rebuilt after a play failure (gen=\$gen)",
        "bluetooth adapter off: links torn down, event scan dead",
        "bluetooth adapter on: scan re-registered, links kickstarted",
        "ebike reader not started: \$startStage",
        "service stopping",
        "scan wake ignored: app not running, service start refused (\$name)",
        "scan wake ignored: Bluetooth permission revoked since the scan started - re-grant \$permission to restore the radar link",
        "eBike status: connect attempt",
        "eBike status: service discovery failed",
        "eBike status: status characteristic not found",
        "eBike status: CCCD subscribe failed",
        "eBike status: subscribed; streaming",
    )

    private val sources: Map<String, String> by lazy { writers.associate { it.file to RepoFiles.mainSource(it.file).readText() } }

    private fun source(w: Writer): String = sources.getValue(w.file)

    private fun literals(w: Writer): List<String> = w.literal.findAll(source(w)).map { it.groupValues[1] }.toList()

    private val literalsByWriter: Map<String, List<String>> by lazy { writers.associate { it.file to literals(it) } }

    @Test
    fun everyFileThatReachesTheJournalIsListed() {
        // The writer list is what "every writer" rests on. A file that opens
        // the journal itself, or takes a `journal` or `log` lambda the service
        // can hand it through, is either a listed writer or a reader; anything
        // else is a writer nobody scans. Exact equality, so the walk cannot
        // decay to finding only the journal's own class.
        val root = checkNotNull(RepoFiles.mainSource("BikeRadarService.kt").parentFile)
        val reaching = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""LinkEventJournal\(|linkJournal|\b(journal|log):\s*\(?\(String\) -> Unit""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toSet()
        assertEquals(writers.map { it.file }.toSet() + readers, reaching)

        // Inside the service every mention is the field, a literal call, a
        // forwarder, or a hand-off by reference to a writer named above; a
        // hand-off to anything else is a new writer. Which constructor each
        // hand-off sits in is not pinned, only how many there are.
        val service = writers.single { it.file == "BikeRadarService.kt" }
        val lines = source(service).lines().filter { "linkJournal" in it }
        val handoffs = lines.count { it.trim() == "journal = linkJournal::log," }
        assertEquals("the service hands its journal by reference to exactly `handedByReference`", handedByReference.size, handoffs)
        assertTrue(handedByReference.all { h -> writers.any { it.file == h } })
        for ((forwarder, fed) in service.forwarders) {
            assertEquals("`$forwarder` feeds one writer, $fed; a second is a new writer", 1, lines.count { forwarder in it })
            assertTrue(writers.any { it.file == fed })
        }
        val unclassified = lines.filter { line ->
            val t = line.trim()
            !t.startsWith("private val linkJournal = LinkEventJournal(") &&
                t != "journal = linkJournal::log," &&
                service.forwarders.keys.none { it in t } &&
                !Regex(service.calls.pattern + """"[^"]*"\)""").containsMatchIn(t)
        }
        assertEquals("service lines using the journal in a shape this test cannot classify", emptyList<String>(), unclassified)
    }

    @Test
    fun everyWriterIsSeenWritingSomething() {
        // Anti-vacuity, per file. A renamed method empties one writer's list,
        // and every comparison below is satisfied by an empty list, so the
        // check would go quiet at the moment it stopped seeing.
        for ((file, found) in literalsByWriter) {
            assertTrue("the journal scan found nothing in $file", found.isNotEmpty())
        }
    }

    @Test
    fun everyJournalCallIsOneThisTestCanSee() {
        // A call whose first argument is not a plain string literal, one built
        // from a val or a conditional, is invisible to the literal regex, so
        // the classification below would pass while the new line went
        // undisclosed. Forwarders are excluded by exact text.
        for (w in writers) {
            val src = source(w)
            val calls = w.calls.findAll(src).count()
            val forwarded = w.forwarders.keys.sumOf { src.split(it).size - 1 }
            assertEquals(
                "a journal call in ${w.file} does not take a plain string literal, " +
                    "so the disclosure check cannot see what it writes",
                calls - forwarded,
                literals(w).size,
            )
        }
    }

    @Test
    fun everyJournalLineIsEitherMachineryOrADeclaredEvent() {
        // The classification. Failing here is the moment an author decides
        // which list a new line belongs to.
        val events = declared.map { it.literal }.toSet()
        for ((file, found) in literalsByWriter) {
            for (literal in found) {
                assertTrue(
                    "$file writes \"$literal\" to the journal, which is neither in `machinery` nor a " +
                        "declared event: add it to one, and if it is an event, the words each " +
                        "locale's disclosure carries for it",
                    literal in machinery || literal in events,
                )
            }
        }
    }

    @Test
    fun theCoordinatorWritesOnlyDeclaredEvents() {
        // The coordinator writes nothing but rider-facing events: a machinery
        // line appearing there is an event in disguise.
        val coordinator = writers.single { it.file == "RadarLinkCoordinator.kt" }
        val events = declared.map { it.literal }.toSet()
        assertEquals(emptyList<String>(), literals(coordinator).filter { it !in events })
    }

    @Test
    fun everyDeclaredEventAndMachineryLineIsStillWritten() {
        // Both lists are exact text, so they rot when a line is reworded: the
        // old entry sits satisfied by nothing while the new wording fails
        // classification. Both halves fail loudly rather than one silently.
        val written = literalsByWriter.values.flatten().toSet()
        val stale = (machinery + declared.map { it.literal }) - written
        assertTrue("entries no writer writes any more: $stale", stale.isEmpty())
    }

    @Test
    fun theEnglishDisclosureCarriesTheMachineryClass() {
        // The sentence that covers every `machinery` line at once, and the
        // antecedent that gives "of the same kind" its scope: every device
        // class a machinery line names, and the data each line carries.
        // Narrowed in either locale, lines would go undisclosed with nothing
        // else red.
        val body = app.getString(R.string.settings_privacy_on_phone_linklog)
        for (phrase in listOf("plus app events of the same kind", "radar", "dashcam", "eBike", "service", "device names", "times", "what happened")) {
            assertTrue("the connection-log disclosure does not mention $phrase: $body", body.contains(phrase))
        }
    }

    @Test
    @Config(qualifiers = "+es")
    fun theSpanishDisclosureCarriesTheMachineryClassToo() {
        val body = app.getString(R.string.settings_privacy_on_phone_linklog)
        for (phrase in listOf("además de eventos de la app del mismo tipo", "radar", "cámara delantera", "eBike", "servicio", "nombres de dispositivo", "horas", "qué ocurrió")) {
            assertTrue("la copia del registro de conexiones no menciona $phrase: $body", body.contains(phrase))
        }
    }

    @Test
    fun noMachineryLineSpeaksOfTheRider() {
        // The classification is the author's call, but a line about the rider
        // or a cue is an event whatever list it was put in.
        val riderFacing = Regex("""\b(rider|alert|alarm|snooze|snoozed|dismiss|dismissed|you|your)\b""", RegexOption.IGNORE_CASE)
        assertEquals(emptyList<String>(), machinery.filter { riderFacing.containsMatchIn(it) })
    }

    @Test
    fun theReadersNeverWrite() {
        // A reader is exempt from the scan by name, so a `.log(` added to one
        // would be classified by nobody.
        for (r in readers) {
            val src = RepoFiles.mainSource(if (r == "DebugScreen.kt") "ui/$r" else r).readText()
            assertEquals("$r writes to the journal and is listed as a reader", 0, Regex("""\.log\(""").findAll(src).count())
        }
    }

    @Test
    fun theEnglishDisclosureCarriesEveryDeclaredEventsWords() {
        val body = app.getString(R.string.settings_privacy_on_phone_linklog)
        for (phrase in declared.map { it.en }.distinct()) {
            assertTrue("the connection-log disclosure does not mention $phrase: $body", body.contains(phrase))
        }
    }

    @Test
    @Config(qualifiers = "+es")
    fun theSpanishDisclosureCarriesThemToo() {
        // Its own test rather than a loop over locales, because the failure
        // this guards is one locale saying something NARROWER than its sibling,
        // and `privacy-disclosure-check.sh` reads `values/` only.
        val body = app.getString(R.string.settings_privacy_on_phone_linklog)
        for (phrase in declared.map { it.es }.distinct()) {
            assertTrue("la copia del registro de conexiones no menciona $phrase: $body", body.contains(phrase))
        }
    }
}
