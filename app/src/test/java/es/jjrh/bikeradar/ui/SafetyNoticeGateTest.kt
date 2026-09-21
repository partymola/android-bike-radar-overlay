// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the notice sits in the launch order, and that the activity really
 * asks this rather than re-deriving it.
 *
 * The case worth the test is the UPGRADING rider: `firstRunComplete` is
 * already true, so any ordering that treats the notice as part of onboarding
 * skips them entirely, and no manual test on a set-up phone would show it.
 */
class SafetyNoticeGateTest {

    @Test
    fun aNewInstallMeetsTheNoticeBeforeOnboarding() {
        assertEquals("safety-notice", startDestination(safetyNoticeAcknowledged = false, firstRunComplete = false))
    }

    @Test
    fun anUpgradingRiderMeetsTheNoticeBeforeTheHomeScreen() {
        assertEquals("safety-notice", startDestination(safetyNoticeAcknowledged = false, firstRunComplete = true))
    }

    @Test
    fun onceAcknowledgedANewInstallGoesToOnboarding() {
        assertEquals("onboarding", startDestination(safetyNoticeAcknowledged = true, firstRunComplete = false))
    }

    @Test
    fun onceAcknowledgedASetUpRiderGoesStraightToTheHomeScreen() {
        assertEquals("main", startDestination(safetyNoticeAcknowledged = true, firstRunComplete = true))
    }

    /**
     * The four cases above are about a function nothing is obliged to call.
     * This is the half that would otherwise be missing: an activity that
     * writes the branch out again passes every one of them while showing the
     * notice to the wrong riders.
     */
    @Test
    fun theActivityRoutesThroughThisGate() {
        val source = RepoFiles.mainSource("MainActivity.kt")
        assertTrue("the activity moved: ${source.absolutePath}", source.isFile)
        val text = source.readText()

        assertTrue(
            "MainActivity no longer calls startDestination(); the launch order is decided somewhere this test cannot see",
            Regex("""val startDest = startDestination\(""").containsMatchIn(text),
        )
        assertTrue(
            "the NavHost no longer starts at what the gate decided",
            Regex("""startDestination = startDest\b""").containsMatchIn(text),
        )
    }

    /**
     * Both halves of the About route, which is the only way a rider re-reads
     * the notice. The row and the graph entry are the only two occurrences of
     * this string in the app, so a rename on either side is a crash on tap
     * that every other gate stays green through: the About golden renders the
     * row but never presses it.
     */
    @Test
    fun theAboutRouteIsRegisteredInTheGraph() {
        val route = "settings/safety"
        val about = RepoFiles.mainSource("ui/SettingsAbout.kt").readText()
        val activity = RepoFiles.mainSource("MainActivity.kt").readText()

        assertTrue(
            "the About row no longer navigates to $route",
            about.contains("""navigate("$route")"""),
        )
        assertTrue(
            "nothing registers $route, so tapping the About row throws",
            activity.contains("""composable("$route")"""),
        )
    }

    /**
     * The acknowledgement has to be written before the rider is sent on, or a
     * process death between the two shows the notice again on the next launch.
     */
    @Test
    fun theAcknowledgementIsSavedBeforeTheRiderIsSentOn() {
        val text = RepoFiles.mainSource("MainActivity.kt").readText()
        val saved = text.indexOf("safetyNoticeAcknowledged = true")
        val navigated = text.indexOf("navigate(startDestination(")
        assertTrue("the acknowledgement write is gone", saved >= 0)
        assertTrue("the post-notice navigate is gone", navigated >= 0)
        assertTrue("the rider is sent on before the acknowledgement is saved", saved < navigated)
    }
}
