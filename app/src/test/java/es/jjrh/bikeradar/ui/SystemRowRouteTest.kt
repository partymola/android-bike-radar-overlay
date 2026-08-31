// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The System-card rows navigate, and a wrong destination is invisible to the
 * compiler: every route is a string, so a typo or a renamed screen sends the
 * rider somewhere else with everything still green.
 *
 * Two halves, and the second is the one that matters. Asserting the literals
 * alone would only prove the function agrees with itself, so the routes are
 * also read back out of the navigation graph in `MainActivity` - if a screen
 * is renamed there, this fails rather than the app dead-ending at runtime.
 */
class SystemRowRouteTest {

    private val navGraph = RepoFiles.mainSource("MainActivity.kt").readText()

    @Test
    fun `each row names its own screen`() {
        // Literals, not the production constants: reading those back would
        // keep this green whatever they became.
        assertEquals("settings/radar-device", systemRowRoute(SystemRowTarget.RADAR))
        assertEquals("settings/dashcam", systemRowRoute(SystemRowTarget.DASHCAM))
        assertEquals("settings/ebike", systemRowRoute(SystemRowTarget.EBIKE))
        assertEquals("settings/ha", systemRowRoute(SystemRowTarget.HA))
    }

    @Test
    fun `every route is registered in the navigation graph`() {
        // The half that cannot be satisfied by the function agreeing with
        // itself. A route absent here dead-ends the tap.
        for (target in SystemRowTarget.entries) {
            val route = systemRowRoute(target)
            assertTrue(
                "route \"$route\" for $target is not registered in MainActivity",
                navGraph.contains("composable(\"$route\")"),
            )
        }
    }

    @Test
    fun `the radar row opens the device screen rather than alert tuning`() {
        // Deliberate, and easy to "correct" later: the alerts screen is also
        // called radar. A rider tapping a row that says "Not in range" wants
        // the pairing and connection state, not the beep thresholds.
        assertEquals("settings/radar-device", systemRowRoute(SystemRowTarget.RADAR))
        assertTrue(
            "the alert-tuning screen exists separately, which is why this is worth pinning",
            navGraph.contains("composable(\"settings/radar\")"),
        )
    }

    @Test
    fun `every target has a distinct destination`() {
        // A copy-paste in the `when` would silently point two rows at one
        // screen, and both taps would still "work".
        val routes = SystemRowTarget.entries.map { systemRowRoute(it) }
        assertEquals("each row must open its own screen: $routes", routes.size, routes.toSet().size)
    }
}
