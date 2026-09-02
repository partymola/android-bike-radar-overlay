// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every hold change is one atomic step, read out of the source.
 *
 * `hide` and `show` are read-modify-writes on a set several threads reach at
 * once: consumers arrive on binder pool threads while the app's own
 * revalidation drops them from a coroutine. Written as `value = value + x` a
 * lost update puts back a package the rider has just had revoked, leaving a
 * hold owned by an app with no grant that does not know it is holding.
 *
 * `RadarOverlayGateTest.concurrentHidesAndShowsNeverResurrectAHold` reaches
 * that race under load, but only when BOTH mutators are plain: with one of the
 * two still atomic its compare-and-set retries over the gap and no load found
 * it. So the reachable half-revert is what this covers, and it is the reason
 * this file exists rather than being folded into that test.
 *
 * `reset` is exempt and named rather than pattern-matched: it writes a constant
 * rather than a function of the current value, so it has nothing to lose.
 */
class RadarOverlayGateIsAtomicTest {

    @Test
    fun everyMutatorThatReadsTheCurrentSetUpdatesItAtomically() {
        val text = RepoFiles.mainSource("ipc/RadarOverlayGate.kt").readText()

        // A POSITIVE assertion, not a banned spelling. Forbidding
        // `_hiddenBy.value = _hiddenBy.value` catches one way of writing the
        // defect and misses at least three others that a maintainer tidying a
        // long line would reach for first: a temp variable, the public alias on
        // the right, and `+=`. Requiring the atomic form instead admits none of
        // them, and it is the assertion the object's own KDoc claims is here.
        for (mutator in listOf("hide", "show")) {
            val body = bodyOf(text, mutator)
            assertTrue(
                "$mutator computes the new set from the current one, so it has to be one " +
                    "step: a lost update resurrects a hold the rider revoked, and nothing " +
                    "throws when it happens. Body was: $body",
                body.contains("_hiddenBy.update"),
            )
        }

        // The exemption, by name, and it is why the loop above is a list rather
        // than every function in the file. `reset` writes a constant, so it has
        // nothing to lose; a blanket rule would forbid it, and a maintainer
        // meeting a rule that forbids correct code deletes the rule.
        val resetBody = bodyOf(text, "reset")
        assertTrue("reset should still clear the set outright: $resetBody", resetBody.contains("emptySet()"))
        assertEquals(
            "if the gate grows a fourth mutator this check has not been told about it",
            3,
            Regex("""\n    fun (\w+)\(""").findAll(text).count(),
        )
    }

    private fun bodyOf(text: String, name: String): String {
        val at = text.indexOf("fun $name(")
        assertTrue("$name is gone from the gate", at >= 0)
        return text.substring(at).substringBefore("\n    }")
    }
}
