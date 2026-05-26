// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the [DataDisclosure] anchor against drift. The anchor is the single
 * source of truth the privacy disclosures key off (see
 * `scripts/privacy-disclosure-check.sh` and the Settings → Privacy screen).
 *
 * The completeness test reads [HaClient]'s source and fails if it publishes a
 * `varia/...` data topic whose family is not registered in the anchor - so a
 * new `publishMqtt("varia/$slug/foo", ...)` cannot ship until `foo` is added
 * to [DataDisclosure.outbound], which in turn forces a Privacy-screen update
 * (enforced by `scripts/privacy-disclosure-check.sh`). This test runs in
 * `testDebugUnitTest`, so CI catches the drift.
 */
class HaClientDataDisclosureTest {

    @Test
    fun everyOutboundTopicFamilyIsRegisteredInTheAnchor() {
        val source = readMainSource("HaClient.kt")
        // Limitation: matches whole "varia/..." string literals only; a
        // dynamically concatenated topic ("varia/" + seg) would slip past.
        // Matches current HaClient style, where every topic is a whole literal.
        val families = Regex("\"varia/[^\"]*\"")
            .findAll(source)
            .map { it.value.trim('"') }
            .map { normaliseFamily(it) }
            .filter { it.isNotEmpty() && it != "_probe" }
            .toSet()

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

    /** Reduce a topic literal to its anchor family: drop the `varia/` prefix, a
     *  trailing `/last`, any `$slug` segment, and flatten remaining `/` to `_`. */
    private fun normaliseFamily(topic: String): String = topic.removePrefix("varia/")
        .removeSuffix("/last")
        .replace("\${slug}/", "")
        .replace("\$slug/", "")
        .replace("\${slug}", "")
        .replace("\$slug", "")
        .replace("/", "_")

    private fun readMainSource(fileName: String): String {
        // Gradle runs unit tests with the module dir (app/) as the working
        // directory; the repo-root fallback covers other runners.
        val file = listOf(
            "src/main/java/es/jjrh/bikeradar/$fileName",
            "app/src/main/java/es/jjrh/bikeradar/$fileName",
        ).map { File(it) }.firstOrNull { it.exists() }
            ?: error("could not locate $fileName from ${File(".").absolutePath}")
        return file.readText()
    }
}
