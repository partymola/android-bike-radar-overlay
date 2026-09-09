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
 * Forces an author adding a rider-facing event to [RadarLinkCoordinator] to
 * choose the words that describe it, in both languages, and fails if either
 * locale then lacks them.
 *
 * That is the whole guarantee, and it is narrower than it looks. It covers ONE
 * file: `RadarLinkController`, `CameraLightLinkController`, `BikeRadarService`,
 * `BatteryScanReceiver` and `RideCheckpoint` all write to the same journal and
 * none is scanned here, so a rider-facing line added in any of them ships
 * undisclosed with this green. The Privacy copy is written illustratively
 * ("such as") rather than as a closed list for exactly that reason. Widening
 * the scan to every writer, with an allow-list for the link-lifecycle lines
 * that dominate them, is the version that would earn a stronger sentence.
 *
 * An instruction in a KDoc to keep the disclosure in step is not a mechanism,
 * because a commit can break it silently. This is the mechanism, for the file
 * where the rider-facing events live.
 */
@RunWith(RobolectricTestRunner::class)
class JournalScopeIsDisclosedTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /**
     * Each event the coordinator writes, with the words each locale's
     * connection-log disclosure has to carry for it.
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

    private fun coordinatorSource(): String = RepoFiles.mainSource("RadarLinkCoordinator.kt").readText()

    private fun literalsInSource(): List<String> = Regex("""journal\(\s*"([^"]*)"""")
        .findAll(coordinatorSource())
        .map { it.groupValues[1] }
        .toList()

    @Test
    fun theCoordinatorWritesExactlyTheEventsThisTestDeclares() {
        assertEquals(declared.map { it.literal }.sorted(), literalsInSource().sorted())
    }

    @Test
    fun theScanActuallyFindsEvents() {
        // Anti-vacuity. Without it the comparison above is satisfied by an
        // empty list on both sides, which is what a renamed method or a moved
        // file produces - the check would go quiet at the moment it stopped
        // being able to see anything.
        assertTrue("the journal scan found nothing to check", literalsInSource().isNotEmpty())
    }

    @Test
    fun everyJournalCallInTheCoordinatorIsOneThisTestCanSee() {
        // The other half of the same worry: a call written across two lines, or
        // built from a val, is invisible to the regex, so the comparison would
        // pass while the new event went undisclosed.
        val calls = Regex("""journal\(""").findAll(coordinatorSource()).count()
        assertEquals(
            "a journal call in the coordinator is not a single-line string literal, " +
                "so the disclosure check cannot see what it writes",
            calls,
            literalsInSource().size,
        )
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
