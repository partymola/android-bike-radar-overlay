// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.access

import android.app.Activity
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.ipc.RadarContract.Consent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * The result a consumer app actually receives.
 *
 * The decisions live in [RadarConsentDecider] and are tested there. What this
 * covers is the part a consumer depends on and the decider cannot see: which
 * result code and extras come back, and that a caller with no name gets one at
 * all rather than a screen. The codes' literal values are pinned in
 * `RadarContractTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RadarConsentActivityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule val compose = createEmptyComposeRule()

    @Before
    fun clearGrants() {
        context.getSharedPreferences(PrefsRadarGrantStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Past the riding-aid notice. Before it this activity shows the notice
        // in front of the question, so the tests below would be looking at the
        // wrong screen. The notice here is covered by `NoScreenBeforeTheNoticeTest`.
        Prefs(context).safetyNoticeAcknowledged = true
    }

    private fun installCaller(packageName: String) {
        val signing = android.content.pm.SigningInfo()
        shadowOf(signing).setSignatures(arrayOf(android.content.pm.Signature(byteArrayOf(7, 7))))
        val info = android.content.pm.PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                this.packageName = packageName
                uid = 4_242
                name = packageName
            }
            signingInfo = signing
        }
        shadowOf(context.packageManager).installPackage(info)
        shadowOf(context.packageManager).setPackagesForUid(4_242, packageName)
    }

    private fun launch(callingPackage: String?): Activity {
        val controller = Robolectric.buildActivity(RadarConsentActivity::class.java)
        shadowOf(controller.get()).setCallingPackage(callingPackage)
        return controller.create().get()
    }

    @Test
    fun aCallerWithNoNameIsToldSoRatherThanShownAScreen() {
        val activity = launch(null)
        val shadow = shadowOf(activity)
        assertEquals(Consent.RESULT_CALLER_UNKNOWN, shadow.resultCode)
        assertTrue("the screen must not stay open for a caller it cannot name", activity.isFinishing)
    }

    @Test
    fun aRefusalCarriesNoGrantInItsExtras() {
        val intent = shadowOf(launch(null)).resultIntent
        assertFalse(intent.getBooleanExtra(Consent.EXTRA_READ, true))
        assertFalse(intent.getBooleanExtra(Consent.EXTRA_CONTROL, true))
    }

    @Test
    fun anAppThatIsNotInstalledIsRefusedRatherThanAsked() {
        val activity = launch("com.example.never.installed")
        assertEquals(Consent.RESULT_CALLER_UNKNOWN, shadowOf(activity).resultCode)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun anIdentifiedAppIsShownTheQuestionRatherThanAnswered() {
        installCaller("com.example.trailbuddy")
        val controller = Robolectric.buildActivity(RadarConsentActivity::class.java)
        shadowOf(controller.get()).setCallingPackage("com.example.trailbuddy")
        val activity = controller.setup().get()
        assertFalse(
            "the rider has not answered yet, so nothing may be returned",
            activity.isFinishing,
        )
        assertNull("nothing may be returned before an answer", shadowOf(activity).resultIntent)
        compose.onNodeWithText("Share your radar?").assertIsDisplayed()
    }

    /** The not-set-up line follows the real onboarding flag, both ways. */
    @Test
    fun aRiderWhoHasNotSetUpBikeRadarIsToldBeforeAnswering() {
        Prefs(context).firstRunComplete = false
        askedBy("com.example.trailbuddy")
        compose.onNodeWithText(NOT_SET_UP, substring = true).assertExists()
    }

    @Test
    fun aRiderWhoHasSetUpBikeRadarIsNotTold() {
        Prefs(context).firstRunComplete = true
        askedBy("com.example.trailbuddy")
        compose.onNodeWithText("Share your radar?").assertIsDisplayed()
        compose.onNodeWithText(NOT_SET_UP, substring = true).assertDoesNotExist()
    }

    private fun askedBy(packageName: String) {
        installCaller(packageName)
        val controller = Robolectric.buildActivity(RadarConsentActivity::class.java)
        shadowOf(controller.get()).setCallingPackage(packageName)
        controller.setup()
    }

    /**
     * RESULT_CANCELED means nothing changed, which is what the contract tells
     * a consumer: the second button, "Cancel" over an existing grant, leaves it
     * standing. Only the primary button's revoke path removes one.
     */
    @Test
    fun cancellingOverAnExistingGrantLeavesItStanding() {
        installCaller("com.example.trailbuddy")
        val store = PrefsRadarGrantStore(
            context.getSharedPreferences(PrefsRadarGrantStore.PREFS_NAME, Context.MODE_PRIVATE),
        )
        store.put(
            RadarGrant(
                packageName = "com.example.trailbuddy",
                certDigest = "digest",
                label = "Trail Buddy",
                grantedAtMs = 1L,
                lastUsedAtMs = 0L,
                read = true,
                control = false,
            ),
        )
        val controller = Robolectric.buildActivity(RadarConsentActivity::class.java)
        shadowOf(controller.get()).setCallingPackage("com.example.trailbuddy")
        val activity = controller.setup().get()

        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.waitForIdle()

        val shadow = shadowOf(activity)
        assertTrue("the button must answer", activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadow.resultCode)
        requireNotNull(shadow.resultIntent.extras) { "the answer must carry extras" }
        val kept = requireNotNull(store.grantFor("com.example.trailbuddy")) { "declining removed the grant" }
        assertTrue(kept.read)
    }

    /**
     * The rider's own no, through the real button. It must stay
     * RESULT_CANCELED and not become one of the refusal codes, because telling
     * the two apart is what those codes are for.
     */
    @Test
    fun theRiderDecliningComesBackAsCancelled() {
        installCaller("com.example.trailbuddy")
        val controller = Robolectric.buildActivity(RadarConsentActivity::class.java)
        shadowOf(controller.get()).setCallingPackage("com.example.trailbuddy")
        val activity = controller.setup().get()

        compose.onNodeWithText("Don't allow").performScrollTo().performClick()
        compose.waitForIdle()

        val shadow = shadowOf(activity)
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadow.resultCode)
        // RESULT_CANCELED is also what Robolectric reports when setResult was
        // never called, so the extras are what prove the button answered.
        val extras = requireNotNull(shadow.resultIntent.extras) { "the answer must carry extras" }
        assertFalse(extras.getBoolean(Consent.EXTRA_READ, true))
        assertFalse(extras.getBoolean(Consent.EXTRA_CONTROL, true))
    }

    private companion object {
        const val NOT_SET_UP = "Bike Radar isn't set up yet."
    }
}
