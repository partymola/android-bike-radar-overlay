// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A granted app may take the DISPLAY, never the warning.
 *
 * `setOverlayVisible(false)` removes the rider's collision-warning overlay
 * while another app draws its own map. Audio is the rider's primary alert
 * channel, so the alert call must stay outside the gate: a maintainer wrapping
 * the loop tail in the same check, or turning the gate into an early
 * `return@collect`, would silence collision beeps whenever a third-party app is
 * holding the overlay hidden, with nothing on screen to reveal it.
 *
 * **This is a STRUCTURAL check, not a behavioural one, and that is a real
 * limitation.** It reads the pipeline's source and requires the alert call to
 * appear after the gate block closes, at the collect body's own indentation. It
 * would not catch a gate pushed down INTO `fireAlertCue`, and it says nothing
 * about whether a cue ever actually sounds.
 *
 * The behavioural version is not available today: no test in
 * `OverlayPipelineDrivingTest` can make the pipeline emit a cue at all - a
 * single frame is suppressed by the sustain gate and the harness's monotonic
 * clock does not advance across frames, so `AlertDecider` never reaches a beep.
 * Closing that is worth doing and is what would replace this file.
 */
class OverlayHideDoesNotReachTheAlertPathTest {

    private fun pipelineSource(): String = RepoFiles.mainSource("OverlayPipeline.kt").readText()

    @Test
    fun theAlertCallIsNotInsideTheOverlayGate() {
        val text = pipelineSource()

        val gateAt = text.indexOf("val hiddenForConsumer = RadarOverlayGate.hidden")
        assertTrue("the overlay gate is no longer consulted in the pipeline", gateAt >= 0)

        val cueAt = text.indexOf(CUE)
        assertTrue("the pipeline no longer fires an alert cue", cueAt >= 0)
        assertTrue("the cue now runs before the gate, so this check reads nothing", cueAt > gateAt)

        // Indentation cannot decide this: the cue legitimately sits inside an
        // `if (!prefs.isPaused)` block at the same depth a gate branch would
        // put it. Nor can "did the brace depth ever return to zero" - the
        // gate's own detach branch closes on the way, so that answer is yes
        // whether or not the cue is wrapped, and a check asking it would
        // certify the property without being able to fail.
        //
        // The question that discriminates: of the blocks still OPEN where the
        // cue sits, does any of them belong to the gate?
        val open = ArrayDeque<Int>()
        for (i in gateAt until cueAt) {
            when (text[i]) {
                '{' -> open.addLast(i)
                '}' -> open.removeLastOrNull()
            }
        }

        // Both spellings: a branch written straight off `RadarOverlayGate.hidden`
        // rather than through the local reads the same to a rider and would
        // walk past a check that only knew the local's name.
        val guardedByTheGate = open.map { brace ->
            val lineStart = text.lastIndexOf('\n', brace).let { if (it < 0) 0 else it + 1 }
            text.substring(lineStart, brace)
        }.filter { it.contains("hiddenForConsumer") || it.contains("RadarOverlayGate") }

        assertTrue(
            "fireAlertCue sits inside a block opened by the overlay gate " +
                "($guardedByTheGate), so hiding the overlay would silence the " +
                "rider's collision beeps",
            guardedByTheGate.isEmpty(),
        )
    }

    @Test
    fun theGateDoesNotShortCircuitTheCollectBody() {
        // The sibling above walks open braces, so a BRACE-LESS early return
        // escapes it entirely. That is the cheap way to write the gate and the
        // wrong one: it would skip the cue along with the view.
        val text = pipelineSource()
        val gateAt = text.indexOf("val hiddenForConsumer = RadarOverlayGate.hidden")
        val cueAt = text.indexOf(CUE)
        val between = text.substring(gateAt, cueAt)

        assertTrue(
            "an early return between the gate and the alert would skip the cue",
            !between.contains("return@collect"),
        )
    }

    private companion object {
        /** One spelling, so the two tests cannot drift onto different call sites. */
        const val CUE = "fireAlertCue("
    }
}
