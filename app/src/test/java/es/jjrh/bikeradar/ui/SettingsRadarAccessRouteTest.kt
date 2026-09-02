// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import es.jjrh.bikeradar.access.RadarGrant
import es.jjrh.bikeradar.access.RadarGrantStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rider answers the consent screen in the ASKING app's task, so this
 * screen is backgrounded while the store changes underneath it and resumed
 * afterwards. Read once at composition it shows the state from before the
 * answer, and the goldens cannot see that: they render the list from whatever
 * they are handed.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRadarAccessRouteTest {

    @get:Rule val composeRule = createComposeRule()

    private class FakeOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** Backed by a list the test mutates, standing in for another task's write. */
    private class FakeStore(var grants: List<RadarGrant>) : RadarGrantStore {
        override fun grantFor(packageName: String) = grants.firstOrNull { it.packageName == packageName }

        override fun all() = grants

        override fun put(grant: RadarGrant): Boolean {
            grants = grants.filterNot { it.packageName == grant.packageName } + grant
            return true
        }

        override fun revoke(packageName: String): Boolean {
            grants = grants.filterNot { it.packageName == packageName }
            return true
        }

        override fun markUsed(packageName: String, atMs: Long) = Unit
    }

    private fun grant(pkg: String, label: String) = RadarGrant(pkg, "aa11", label, 0L, 0L, read = true, control = false)

    private val owner = FakeOwner()

    private fun show(store: RadarGrantStore) {
        owner.registry.currentState = Lifecycle.State.RESUMED
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                UiTheme { SettingsRadarAccessRoute(store = store, onBack = {}) }
            }
        }
        composeRule.waitForIdle()
    }

    private fun leaveAndComeBack() {
        owner.registry.currentState = Lifecycle.State.CREATED
        composeRule.waitForIdle()
        owner.registry.currentState = Lifecycle.State.RESUMED
        composeRule.waitForIdle()
    }

    /**
     * Paused but not stopped, which is what a translucent activity, split
     * screen or a freeform window gives. Every other transition here goes all
     * the way down to CREATED and so crosses ON_START as well, which a
     * STARTED-gated re-read would also catch - this is the one that tells the
     * two apart.
     */
    private fun pauseAndResume() {
        owner.registry.currentState = Lifecycle.State.STARTED
        composeRule.waitForIdle()
        owner.registry.currentState = Lifecycle.State.RESUMED
        composeRule.waitForIdle()
    }

    @Test
    fun anAppAllowedWhileTheScreenWasAwayIsListedOnReturn() {
        val store = FakeStore(emptyList())
        show(store)
        composeRule.onNodeWithText("Trail Buddy").assertDoesNotExist()

        store.put(grant("com.example.trailbuddy", "Trail Buddy"))
        leaveAndComeBack()

        composeRule.onNodeWithText("Trail Buddy").assertExists()
    }

    @Test
    fun anAppAllowedWhileTheScreenWasMerelyPausedIsListedOnResume() {
        val store = FakeStore(emptyList())
        show(store)

        store.put(grant("com.example.trailbuddy", "Trail Buddy"))
        pauseAndResume()

        composeRule.onNodeWithText("Trail Buddy").assertExists()
    }

    @Test
    fun anAppRemovedWhileTheScreenWasAwayIsGoneOnReturn() {
        val store = FakeStore(listOf(grant("com.example.trailbuddy", "Trail Buddy")))
        show(store)
        composeRule.onNodeWithText("Trail Buddy").assertExists()

        store.revoke("com.example.trailbuddy")
        leaveAndComeBack()

        composeRule.onNodeWithText("Trail Buddy").assertDoesNotExist()
    }

    @Test
    fun revokingFromTheListUpdatesItWithoutLeavingTheScreen() {
        val store = FakeStore(listOf(grant("com.example.trailbuddy", "Trail Buddy")))
        show(store)

        composeRule.onAllNodesWithContentDescription("Stop sharing")[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("Stop sharing") and hasAnyAncestor(isDialog())).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Trail Buddy").assertDoesNotExist()
    }
}
