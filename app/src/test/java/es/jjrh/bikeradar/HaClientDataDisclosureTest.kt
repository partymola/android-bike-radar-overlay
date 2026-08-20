// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the [DataDisclosure] anchor against drift. The anchor is the single
 * source of truth the privacy disclosures key off (see
 * `scripts/privacy-disclosure-check.sh` and the Settings → Privacy screen).
 *
 * The completeness test reads [HaClient]'s source and fails if it publishes a
 * namespaced data topic whose family is not registered in the anchor - so a
 * new `publishMqtt("$NS/$slug/foo", ...)` cannot ship until `foo` is added
 * to [DataDisclosure.outbound], which in turn forces a Privacy-screen update
 * (enforced by `scripts/privacy-disclosure-check.sh`). This test runs in
 * `testDebugUnitTest`, so CI catches the drift.
 */
class HaClientDataDisclosureTest {

    @Test
    fun everyOutboundTopicFamilyIsRegisteredInTheAnchor() {
        val source = readMainSource("HaClient.kt")
        // Whole "$NS/..." literals, in either interpolation form - the brace
        // form is already used elsewhere in that file for entity ids, so a
        // topic written that way must not slip past. A concatenated topic
        // ("$NS/" + seg) still would; the non-empty assertion below catches
        // only total silence, not that case.
        val families = Regex("\"\\\$\\{?NS\\}?/[^\"]*\"")
            .findAll(source)
            .map { it.value.trim('"') }
            .map { normaliseFamily(it) }
            .filter { it.isNotEmpty() && it != "_probe" }
            .toSet()

        assertTrue(
            "found no namespaced topic literals in HaClient - the scan pattern no longer " +
                "matches how topics are written, so this guard is passing vacuously",
            families.isNotEmpty(),
        )

        val registered = DataDisclosure.outbound.map { it.topicFamily }.toSet()
        val undisclosed = families - registered
        assertTrue(
            "HaClient publishes outbound topic families absent from DataDisclosure.outbound: " +
                "$undisclosed. Register each in the anchor AND disclose it in SettingsPrivacy.kt.",
            undisclosed.isEmpty(),
        )
    }

    @Test
    fun anchorEntriesAreWellFormedAndUnique() {
        val outbound = DataDisclosure.outbound
        assertTrue("anchor must not be empty", outbound.isNotEmpty())
        outbound.forEach {
            assertTrue("blank topicFamily in $it", it.topicFamily.isNotBlank())
            assertTrue("blank category in $it", it.category.isNotBlank())
            assertTrue("blank disclosureKeyword in $it", it.disclosureKeyword.isNotBlank())
        }
        val families = outbound.map { it.topicFamily }
        assertEquals("duplicate topicFamily in anchor", families.size, families.toSet().size)
    }

    /** Reduce a topic literal to its anchor family: drop the `$NS/` prefix, a
     *  trailing `/last`, any `$slug` segment, and flatten remaining `/` to `_`. */
    private fun normaliseFamily(topic: String): String = topic.removePrefix("\$NS/")
        .removeSuffix("/last")
        .replace("\${slug}/", "")
        .replace("\$slug/", "")
        .replace("\${slug}", "")
        .replace("\$slug", "")
        .replace("/", "_")

    private fun readMainSource(fileName: String): String = RepoFiles.mainSource(fileName).readText()
}
