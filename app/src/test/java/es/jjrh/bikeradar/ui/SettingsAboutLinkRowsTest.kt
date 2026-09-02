// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which address each About row actually opens.
 *
 * `LegalScreenLinksTest` pins the constants and the goldens pin the labels, so
 * swapping two of the arguments passes every other gate: the rows read the
 * same, the pixels are identical, and "This app's licence" opens the release
 * notes. Only tapping the row and reading what it asked for can tell.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsAboutLinkRowsTest {

    @get:Rule val composeRule = createComposeRule()

    private var opened: String? = null

    private fun urlOpenedByTapping(label: String): String? {
        composeRule.setContent {
            UiTheme {
                SettingsAboutBody(navController = rememberNavController()) { opened = it }
            }
        }
        composeRule.waitForIdle()

        // The legal rows sit below the fold on this screen.
        composeRule.onNodeWithText(label).performScrollTo().performClick()
        composeRule.waitForIdle()
        return opened
    }

    @Test
    fun theLicenceRowOpensTheLicenceText() {
        assertEquals("https://www.gnu.org/licenses/gpl-3.0.html", urlOpenedByTapping("This app's licence"))
    }

    @Test
    fun theWhatsNewRowOpensTheReleaseNotes() {
        assertEquals(
            "https://github.com/partymola/android-bike-radar-overlay/releases",
            urlOpenedByTapping("What's new"),
        )
    }

    @Test
    fun theRepoChipOpensTheRepo() {
        assertEquals(
            "https://github.com/partymola/android-bike-radar-overlay",
            urlOpenedByTapping("github.com/partymola/android-bike-radar-overlay"),
        )
    }
}
