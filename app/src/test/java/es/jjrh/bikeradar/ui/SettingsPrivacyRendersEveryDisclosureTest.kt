// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every `settings_privacy_*` string is REFERENCED by the screen.
 *
 * Reference, not render: this reads the source, so a key inside a comment or
 * in a branch that never runs would satisfy it. The screen is one linear body
 * today, which is what makes that gap small. What it cannot see at all is a
 * disclosure deleted from `strings.xml` AND from the screen together - the key
 * then leaves this list too, and only the tokens
 * `scripts/privacy-disclosure-check.sh` names are backstopped.
 *
 * `scripts/privacy-disclosure-check.sh` proves those strings EXIST and agree
 * with the manifest and the MQTT anchor. It never opens `SettingsPrivacy.kt`,
 * so deleting a `PrivacyP(stringResource(...))` call leaves the string in
 * `strings.xml`, keeps that gate green, and silently removes a disclosure from
 * the one screen a rider reads to check what the app does. The screen's single
 * golden covers only the top of a screen taller than a viewport, so it does
 * not see the loss either.
 *
 * The key list comes from the generated `R.string` rather than a copy kept
 * here, so a disclosure added tomorrow is covered without anyone remembering
 * this file.
 */
class SettingsPrivacyRendersEveryDisclosureTest {

    private val prefix = "settings_privacy_"

    private fun disclosureKeys(): List<String> = R.string::class.java.fields
        .map { it.name }
        .filter { it.startsWith(prefix) }
        .sorted()

    @Test
    fun everyDisclosureStringIsReferencedByTheScreen() {
        val source = RepoFiles.mainSource("ui/SettingsPrivacy.kt")
        assertTrue("the screen moved: ${source.absolutePath}", source.isFile)
        val text = source.readText()

        // Whole-identifier match: plain `contains` would let a longer key with
        // the same prefix satisfy a shorter one, so adding
        // `settings_privacy_source_label_extra` would silently cover
        // `settings_privacy_source_label`. No such pair exists today, which is
        // exactly why it would go unnoticed.
        val missing = disclosureKeys().filterNot { key ->
            Regex("R\\.string\\.${Regex.escape(key)}\\b").containsMatchIn(text)
        }

        assertTrue(
            "these disclosure strings exist but nothing on the Privacy screen renders them: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun thereAreDisclosureStringsToCheck() {
        // Deliberately a token floor rather than the real count. This exists
        // only to catch a prefix rename that makes the filter match nothing -
        // zero keys, zero missing, green. A floor set near the true count
        // reddens on any ordinary consolidation of strings, and the cheapest
        // response to that is to lower the number, which retires the guard.
        assertTrue(
            "no $prefix* strings found, so the check above proved nothing",
            disclosureKeys().size >= 5,
        )
    }
}
