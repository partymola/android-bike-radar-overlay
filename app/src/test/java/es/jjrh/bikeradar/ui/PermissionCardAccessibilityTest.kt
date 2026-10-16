// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.Manifest
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What TalkBack and a thumb get from the permission card and setup's
 * permissions step: every action is a button named for what it acts on and at
 * least 48 dp each way, every card says in words what it needs or has,
 * and a held Continue says why. A golden cannot see any of this.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PermissionCardAccessibilityTest {

    @get:Rule val compose = createComposeRule()

    private val nearby = PERMISSIONS.single { Manifest.permission.BLUETOOTH_CONNECT in it.permissions }
    private val location = PERMISSIONS.single { Manifest.permission.ACCESS_COARSE_LOCATION in it.permissions }

    private fun card(spec: PermissionSpec, granted: Boolean, alternative: PermissionAlternative? = null, permanentlyDenied: Boolean = false) {
        compose.setContent {
            UiTheme { PermissionCardContent(spec = spec, granted = granted, permanentlyDenied = permanentlyDenied, onAction = {}, alternative = alternative) }
        }
        compose.waitForIdle()
    }

    private fun alternative(summary: String?) = PermissionAlternative(
        actionLabelRes = R.string.settings_lights_loc_enter_coords,
        setTitleRes = R.string.settings_lights_loc_manual_set_title,
        summary = summary,
        onEnter = {},
        onClear = {},
    )

    private fun SemanticsNodeInteraction.isAFullSizeButton(): SemanticsNodeInteraction {
        assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertHasClickAction()
        val node = fetchSemanticsNode()
        val minPx = 48 * compose.density.density
        assertTrue("${node.config} is ${node.size.height}px tall, under 48 dp", node.size.height >= minPx - 0.5f)
        assertTrue("${node.config} is ${node.size.width}px wide, under 48 dp", node.size.width >= minPx - 0.5f)
        return this
    }

    @Test
    fun theNearbyDevicesCardIsMarkedRequired() {
        card(nearby, granted = false)
        compose.onNodeWithText("Required", ignoreCase = true).assertExists()
    }

    @Test
    fun theActionIsATallButtonNamingThePermission() {
        card(nearby, granted = false)
        compose.onNodeWithContentDescription("Grant: Nearby devices").isAFullSizeButton()
    }

    @Test
    fun theCoordinatesButtonsAreTallButtonsNamingThePermission() {
        card(location, granted = false, alternative = alternative(summary = null))
        compose.onNodeWithContentDescription("Enable: Approximate location").isAFullSizeButton()
        compose.onNodeWithContentDescription("Enter coordinates: Approximate location").isAFullSizeButton()
    }

    @Test
    fun theSetCoordinatesRowsButtonsAreTallButtonsNamingWhatTheyChange() {
        // They act on the entered location, not the permission: "Clear:
        // Approximate location" would sound like a revoke.
        card(location, granted = true, alternative = alternative(summary = "51.5074, -0.1278"))
        compose.onNodeWithContentDescription("Change: Manual location").isAFullSizeButton()
        compose.onNodeWithContentDescription("Clear: Manual location").isAFullSizeButton()
    }

    @Test
    fun aPermanentlyRefusedCardsButtonIsNamedForWhereItGoes() {
        card(nearby, granted = false, permanentlyDenied = true)
        compose.onNodeWithContentDescription("Open App info: Nearby devices").isAFullSizeButton()
    }

    @Test
    fun aPermanentlyRefusedNotificationsButtonIsNamedForTheirSettings() {
        card(PERMISSIONS.single { Manifest.permission.POST_NOTIFICATIONS in it.permissions }, granted = false, permanentlyDenied = true)
        compose.onNodeWithContentDescription("Open notification settings: Notifications").isAFullSizeButton()
    }

    @Test
    @Config(qualifiers = "es-w360dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun theMarkStaysOnOneLineAtTwiceTheFontSize() {
        card(nearby, granted = false)
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("NECESARIOS", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("the mark broke across lines", 1, results.single().lineCount)
        // The whole word on that line, and the line against its box, not
        // hasVisualOverflow: FlowRow lays the text out against a wider
        // constraint than the box it settles in, so that flag is set with
        // every glyph inside.
        val r = results.single()
        assertEquals("the mark lost letters", "NECESARIOS".length, r.getLineEnd(0))
        assertTrue("the mark is clipped: ${r.getLineRight(0)}px in a ${r.size.width}px box", r.getLineRight(0) <= r.size.width)
    }

    @Test
    fun aGrantedCardSaysSoToTalkBack() {
        // In the same stop as the title, so TalkBack reads them together.
        card(nearby, granted = true)
        compose.onNode(hasContentDescription("Granted") and hasText("Nearby devices")).assertExists()
    }

    @Test
    fun aCardNotGrantedDoesNotSayGranted() {
        card(nearby, granted = false)
        compose.onNodeWithContentDescription("Granted").assertDoesNotExist()
    }

    private val fakeRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: androidx.core.app.ActivityOptionsCompat?,
            ) {}
        }
    }

    private fun step(nearbyGranted: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeRegistryOwner) {
                UiTheme {
                    PermissionsStepContent(
                        states = listOf(nearby to nearbyGranted),
                        requiredGranted = nearbyGranted,
                        onContinue = {},
                        onPermissionChanged = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aHeldContinueSaysWhyRightAboveItAndPointsAtSkip() {
        step(nearbyGranted = false)
        val hint = compose.onNodeWithText("Grant Nearby devices to continue, or tap Skip setup.").fetchSemanticsNode()
        val cont = compose.onNodeWithText("Continue")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsNotEnabled()
            .fetchSemanticsNode()
        // Directly above it, so TalkBack reaches the reason before the button.
        val hintBottom = hint.positionInRoot.y + hint.size.height
        assertTrue("the hint must sit above Continue", hintBottom <= cont.positionInRoot.y + 0.5f)
        assertTrue("and nothing between them", cont.positionInRoot.y - hintBottom < 48 * compose.density.density)
    }

    @Test
    fun bothButtonsOfTheTwoButtonFooterAreButtons() {
        // Setup's other steps end on this footer.
        compose.setContent {
            UiTheme { FooterCtaDual(primary = "Finish", secondary = "Skip for now", primaryEnabled = false, onPrimary = {}, onSecondary = {}) }
        }
        compose.waitForIdle()
        for (label in listOf("Finish", "Skip for now")) {
            compose.onNodeWithText(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }
    }

    @Test
    fun anOpenContinueSaysNothingMore() {
        step(nearbyGranted = true)
        compose.onNodeWithText("Grant Nearby devices to continue, or tap Skip setup.").assertDoesNotExist()
    }
}
