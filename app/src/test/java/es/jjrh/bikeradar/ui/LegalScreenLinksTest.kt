// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The addresses the legal screens send a rider to.
 *
 * Nothing else checks them: a typo compiles, renders, opens a browser, and
 * lands on a 404 that reads as the licence being unavailable.
 */
class LegalScreenLinksTest {

    @Test
    fun theRepoAddressIsThisRepo() {
        assertEquals("https://github.com/partymola/android-bike-radar-overlay", REPO_URL)
    }

    @Test
    fun theChangelogGoesToTheReleasePage() {
        // Releases rather than CHANGELOG.md, because the release workflow
        // copies each section into the release body and that is the readable
        // copy.
        assertEquals("https://github.com/partymola/android-bike-radar-overlay/releases", RELEASES_URL)
    }

    @Test
    fun theAppsOwnLicenceGoesToTheGplText() {
        assertEquals("https://www.gnu.org/licenses/gpl-3.0.html", GPL_URL)
    }

    @Test
    fun everyListedLibraryHasAnAddressForItsLicence() {
        val entries = LANGUAGE_RUNTIME_LICENCES + ANDROID_PLATFORM_LICENCES + UI_LICENCES
        assertTrue("no entries to check", entries.isNotEmpty())
        for (entry in entries) {
            assertTrue("${entry.name} has no licence address", entry.url.startsWith("https://"))
        }
    }

    @Test
    fun anApacheEntryPointsAtTheApacheText() {
        // Every entry is Apache 2.0 today. `LicenseEntry` declares `url` with no
        // default, so the constructor is what refuses an entry that names none;
        // this only checks that the ones present point at the right text.
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", APACHE_2_URL)
        val apache = (LANGUAGE_RUNTIME_LICENCES + ANDROID_PLATFORM_LICENCES + UI_LICENCES)
            .filter { it.licence == "Apache 2.0" }
        assertTrue("no Apache entries", apache.isNotEmpty())
        for (entry in apache) {
            assertEquals("${entry.name} points somewhere else", APACHE_2_URL, entry.url)
        }
    }
}
