// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which sticky bit the capture-log share dialog reads.
 *
 * The dialog is the rider's consent moment before a capture log leaves the
 * phone, and its body now names the radar's hardware identifiers. Reading the
 * pre-existing `captureLogShareWarningSeen` would silently skip that body for
 * every rider who dismissed the older dialog - which is precisely the rider
 * filing a hardware report, so the failure is invisible and lands on the people
 * the feature is for.
 *
 * Source-reading rather than behavioural because the gate lives in a lambda
 * inside a private Composable with no test entry point. That is a real
 * limitation and this is the weaker check, but the alternative measured here
 * was no check at all: a mutation swapping the two keys survived the whole
 * suite. See [SettingsPrivacyLogcatGuardTest] for the same pattern.
 */
class CaptureLogShareWarningGateTest {

    private val source = RepoFiles.mainSource("ui/DebugScreen.kt").readText()

    @Test
    fun theShareGateReadsTheV2StickyBit() {
        assertTrue(
            "the share dialog must gate on captureLogShareWarningSeenV2; gating on the " +
                "original key skips the hardware-identifier disclosure for every rider " +
                "who dismissed the older dialog",
            source.contains("warningSeen = prefs.captureLogShareWarningSeenV2"),
        )
    }

    @Test
    fun theOriginalKeyIsWrittenButNeverRead() {
        // Still written so a downgrade does not re-prompt. A READ of it would
        // mean the gate had drifted back.
        val reads = source.lines().filter {
            it.contains("captureLogShareWarningSeen") &&
                !it.contains("captureLogShareWarningSeenV2") &&
                !it.contains("prefs.captureLogShareWarningSeen = ")
        }
        assertEquals(
            "the original share-warning key may be written, never read. Found: $reads",
            emptyList<String>(),
            reads,
        )
    }

    /**
     * The gate must not be keyed on the transcript toggle, which was tried and
     * is wrong in both directions. The toggle describes what the app is
     * recording NOW, while the dialog is about what the file in the rider's
     * hand contains: a transcript recorded earlier and shared after the toggle
     * went off would skip the disclosure, and a plain ride log shared while the
     * toggle happens to be on would claim identifiers it does not carry.
     */
    @Test
    fun theShareGateDoesNotDependOnTheTranscriptToggle() {
        val gateLine = source.lines().firstOrNull { it.contains("captureLogShareWarningSeenV2") }
        assertTrue("expected the share gate to still exist", gateLine != null)
        assertTrue(
            "the share gate must not read setupTranscriptEnabled: that predicate is false " +
                "exactly when the transcript becomes shareable. Line: ${gateLine?.trim()}",
            !gateLine!!.contains("setupTranscriptEnabled"),
        )
    }
}
