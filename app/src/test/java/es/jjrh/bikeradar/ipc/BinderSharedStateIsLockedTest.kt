// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every touch of the binder's three plain collections is inside the lock.
 *
 * `failing`, `lastStamped` and `consumerIndices` are a `mutableSetOf` and two
 * `mutableMapOf`, reached by the frame feed on a background dispatcher, by the
 * revalidation collector, and by every interface method on a binder pool
 * thread. Their KDocs say the lock is what makes that safe, so the lock is a
 * claim about the file rather than a local habit, and a claim is what this
 * checks.
 *
 * Written as a check on the CLASS rather than a test per collection, because
 * the two escapes that made it necessary were both introduced by fixes for
 * something else: a helper called one line above the `synchronized` block it
 * belonged in, and a lookup shared between a locked caller and an unlocked one.
 * Neither throws, neither loses data a rider can see, and neither is reachable
 * from a test - `RadarIpcBinderConcurrencyTest` drives that exact pairing
 * hundreds of times and cannot see it, because a plain map races silently.
 * Reading the source is the only instrument that can.
 *
 * What it cannot do, both halves of one limit: it checks LEXICAL enclosure, so
 * it would read as protected either a touch inside a lambda that is defined in
 * a locked block and invoked elsewhere, or a locked function handing one of
 * these collections out to a caller that then mutates it off the lock. Neither
 * shape exists here - every lambda in a locked block goes to an inline function
 * invoked in place, and all three fields are private with no accessor returning
 * them - and if one arrives, this check is why it has to be argued rather than
 * assumed.
 */
class BinderSharedStateIsLockedTest {

    @Test
    fun everyTouchOfTheSharedCollectionsIsUnderTheLock() {
        val text = RepoFiles.mainSource("ipc/RadarIpcBinder.kt").readText()
        val locked = lockedRanges(text)

        var checked = 0
        for (field in FIELDS) {
            // The declaration itself is a touch by this crude measure and is
            // not one, so it is skipped by name rather than by position.
            val declaration = text.indexOf("val $field")
            assertTrue("$field is gone from the binder", declaration >= 0)

            Regex("""\b$field\b""").findAll(text)
                .map { it.range.first }
                .filter { it != declaration + "val ".length }
                .filterNot { inKDoc(text, it) }
                .forEach { at ->
                    checked++
                    assertTrue(
                        "$field is reached off the lock at offset $at, in: ${lineAt(text, at)}. " +
                            "These three are plain collections shared by the frame feed, the " +
                            "revalidation collector and every binder thread, and their KDocs " +
                            "say the lock is what makes that safe.",
                        locked.any { at in it },
                    )
                }
        }

        assertTrue("the fields moved and this check read nothing", checked >= FIELDS.size)
    }

    /** Character ranges covered by a `synchronized(broadcastLock) { ... }` block. */
    private fun lockedRanges(text: String): List<IntRange> = buildList {
        var from = 0
        while (true) {
            val at = text.indexOf(LOCK, from)
            if (at < 0) return@buildList
            val open = text.indexOf('{', at)
            var depth = 0
            var i = open
            while (i < text.length) {
                if (text[i] == '{') depth++
                if (text[i] == '}') {
                    depth--
                    if (depth == 0) break
                }
                i++
            }
            add(open..i)
            from = at + LOCK.length
        }
    }

    private fun inKDoc(text: String, at: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', at).let { if (it < 0) 0 else it + 1 }
        val prefix = text.substring(lineStart, at).trimStart()
        return prefix.startsWith("*") || prefix.startsWith("//") || prefix.startsWith("/**")
    }

    private fun lineAt(text: String, at: Int): String {
        val start = text.lastIndexOf('\n', at).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        return text.substring(start, end).trim()
    }

    @Test
    fun theCheckKnowsWhichCollectionsItGuards() {
        // A fourth plain collection added to this file is not covered by
        // anything above, and would be exactly the shape of the two escapes
        // that made this file necessary.
        val text = RepoFiles.mainSource("ipc/RadarIpcBinder.kt").readText()
        // Deliberately loose about HOW the collection is spelled. Matching only
        // `= mutableSetOf` would let the same field arrive type-annotated, as a
        // `var`, as a `mutableListOf`, or as a bare `HashMap()`, and then this
        // check and the one above would both stay green over an unguarded
        // fourth collection. The residual fitting was in this predicate rather
        // than in the field list.
        val declared = Regex(
            """private va[lr] (\w+)(?:\s*:[^=]+)?\s*=\s*(?:mutable\w+Of|(?:Linked)?(?:Hash|Array)\w*\()""",
        ).findAll(text).map { it.groupValues[1] }.toSet()

        assertEquals("a plain collection here is shared state; add it to FIELDS", FIELDS, declared)
    }

    @Test
    fun theLockScannerCanTellInsideFromOutside() {
        // The check's own parser, which is the one function whose silent
        // misbehaviour makes everything above vacuous. Both mutants for this
        // file mutate the SUBJECT, so they prove it notices a change to the
        // binder and say nothing about whether `lockedRanges` is generous in a
        // way this particular file never exposes.
        val fixture = """
            fun a() {
                synchronized(broadcastLock) {
                    inside()
                    if (x) { nested() }
                }
                outside()
            }
        """.trimIndent()

        val ranges = lockedRanges(fixture)
        assertEquals("one locked block", 1, ranges.size)
        assertTrue("a call in the block is inside", ranges.any { fixture.indexOf("inside()") in it })
        assertTrue("a call in a nested block is inside", ranges.any { fixture.indexOf("nested()") in it })
        assertTrue(
            "a call just past the closing brace must read as UNLOCKED, or the whole check passes over everything",
            ranges.none { fixture.indexOf("outside()") in it },
        )
    }

    private companion object {
        const val LOCK = "synchronized(broadcastLock)"
        val FIELDS = setOf("failing", "lastStamped", "consumerIndices")
    }
}
