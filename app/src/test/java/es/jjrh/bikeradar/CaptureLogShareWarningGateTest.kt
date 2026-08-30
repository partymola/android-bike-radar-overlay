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
 * Source-reading covers the CALL SITE only: that the V2 key is what gets passed
 * to the gate. The gate itself now lives in `onCaptureLogShareRequested` and is
 * exercised behaviourally by DebugCaptureLogListingTest, which is the half this
 * file could never reach. Both are needed - hardcoding the argument here kills
 * the source pin, inverting the branch there kills the behavioural one. The
 * alternative measured before either existed was no check at all: a mutation
 * swapping the two keys survived the whole
 * suite. See [SettingsPrivacyLogcatGuardTest] for the same pattern.
 */
class CaptureLogShareWarningGateTest {

    private val source = RepoFiles.mainSource("ui/DebugScreen.kt").readText()
    private val serviceSource = RepoFiles.mainSource("BikeRadarService.kt").readText()

    /**
     * The service must actually INSTALL the flush hook the Debug screen shares
     * through, and clear it on teardown.
     *
     * Source-reading because the service is not constructible under this
     * harness, and the alternative measured here was no check at all: deleting
     * the install line survives the whole suite, and it silently reverts the
     * feature the hook exists for. A shared in-progress log then ends at the
     * last periodic flush, and on a parked reconnect loop - a radar switched
     * off, which is the reporter's own scenario - no periodic flush ever fires
     * again, so the staleness is unbounded rather than one window.
     */
    @Test
    fun theServiceInstallsAndClearsTheFlushHook() {
        assertTrue(
            "BikeRadarService must install flushCaptureLogForUi, or a shared " +
                "in-progress log silently loses its buffered tail",
            serviceSource.contains("flushCaptureLogForUi = { captureLog.flushNow() }"),
        )
        assertTrue(
            "BikeRadarService.onDestroy must clear flushCaptureLogForUi",
            serviceSource.contains("flushCaptureLogForUi = null"),
        )
    }

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
