// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.access.PrefsRadarGrantStore
import es.jjrh.bikeradar.access.RadarConsentActivity
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.ipc.RadarContract.Consent
import org.junit.After
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.GraphicsMode

/**
 * A rider who has not tapped through the notice can reach no ACTIVITY of this
 * app without meeting it first.
 *
 * Not "no screen": the service draws the ride overlay and posts its
 * notification for an unacknowledged rider, deliberately, and
 * `MainActivitySmokeTest.theServiceStillStartsWhileTheNoticeIsUp` pins that.
 * Withholding a live safety aid to enforce a disclaimer is the worse failure.
 *
 * The manifest declares exactly two activities. This file gates the consent
 * one and asserts only the EXISTENCE of the launcher; the launcher's own
 * gating is pinned by `SafetyNoticeGateTest`, `MainActivitySmokeTest` and
 * `SafetyNoticeAcknowledgeTest`. The count is what is load-bearing here:
 * `theseAreTheOnlyTwoWaysIn` fails when a third is added, so a new entry
 * point cannot quietly bypass the notice.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoScreenBeforeTheNoticeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @get:Rule val compose = createEmptyComposeRule()

    @Before
    @After
    fun clearState() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        app.getSharedPreferences(PrefsRadarGrantStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        RadarStateBus.clear()
    }

    @Test
    fun theseAreTheOnlyTwoWaysIn() {
        val pm = app.packageManager
        // Ours only. The debug manifest also merges androidx's PreviewActivity
        // and the compose-test ComponentActivity, neither of which ships in a
        // release and neither of which is ours to gate.
        val declared = pm.getPackageInfo(
            app.packageName,
            android.content.pm.PackageManager.GET_ACTIVITIES,
        ).activities.orEmpty()
            .map { it.name }
            .filter { it.startsWith("es.jjrh.bikeradar") }
            .toSet()

        assertEquals(
            "a new activity is a new way into the app, and it must show the " +
                "notice before anything else while Prefs.safetyNoticeAcknowledged is false: $declared",
            setOf(MainActivity::class.java.name, RadarConsentActivity::class.java.name),
            declared,
        )
    }

    /**
     * Which screen comes first, for a caller the decider accepts and an
     * install that has not acknowledged the notice. The refusal tests below
     * cannot see the gate, because the decider turns their caller or moment
     * away on its own.
     *
     * The second half runs the SAME fixture with the flag set. It proves the
     * question really renders, so the `assertDoesNotExist` above is not
     * vacuous, and pins that it comes without the notice once acknowledged.
     * The child count above is the positive control for [assertNothingComposed].
     */
    @Test
    fun anAppThisRiderCouldGrantMeetsTheNoticeBeforeTheQuestion() {
        installCaller("com.example.trailbuddy")
        launch("com.example.trailbuddy").use {
            val activity = it.get()
            assertFalse("the rider has not answered anything yet", activity.isFinishing)
            compose.onNodeWithText("Before you ride").assertIsDisplayed()
            compose.onNodeWithText("Share your radar?").assertDoesNotExist()
            assertFalse("showing the notice must not acknowledge it", Prefs(app).safetyNoticeAcknowledged)
            assertTrue(
                "the content view must report a composed screen, or assertNothingComposed cannot fail",
                activity.findViewById<ViewGroup>(android.R.id.content).childCount > 0,
            )
        }

        Prefs(app).safetyNoticeAcknowledged = true
        launch("com.example.trailbuddy").use {
            compose.onNodeWithText("Share your radar?").assertIsDisplayed()
            compose.onNodeWithText("Before you ride").assertDoesNotExist()
        }
    }

    /** The real button, in the consent activity, as a rider sent over from another app. */
    @Test
    fun tappingThroughTheNoticeStoresItAndAsksTheQuestion() {
        installCaller("com.example.trailbuddy")
        launch("com.example.trailbuddy").use {
            val activity = it.get()
            compose.onNodeWithText("I understand").performClick()
            compose.waitForIdle()

            assertTrue("the tap did not reach the stored flag", Prefs(app).safetyNoticeAcknowledged)
            compose.onNodeWithText("Share your radar?").assertIsDisplayed()
            compose.onNodeWithText("I understand").assertDoesNotExist()
            assertFalse("acknowledging is not an answer to the question", activity.isFinishing)
        }
    }

    /** The whole visit a consumer sends a new rider on: the notice, then a grant, then the answer back. */
    @Test
    fun aRiderWhoHasNotSeenTheNoticeCanGrantInOneVisit() {
        installCaller("com.example.trailbuddy")
        launch("com.example.trailbuddy").use {
            val activity = it.get()
            compose.onNodeWithText("I understand").performClick()
            compose.onNodeWithText("See what the radar sees").performScrollTo().performClick()
            compose.onNodeWithText("Allow").performScrollTo().performClick()
            compose.waitForIdle()

            val shadow = shadowOf(activity)
            assertTrue(activity.isFinishing)
            assertEquals(Activity.RESULT_OK, shadow.resultCode)
            val extras = requireNotNull(shadow.resultIntent.extras) { "the answer must carry extras" }
            assertTrue("read grant", extras.getBoolean(Consent.EXTRA_READ, false))
            assertFalse("no control grant", extras.getBoolean(Consent.EXTRA_CONTROL, true))

            // Told OK, so it must be stored: a consumer told OK with nothing
            // behind it is refused on every later call.
            val stored = requireNotNull(
                PrefsRadarGrantStore(app.getSharedPreferences(PrefsRadarGrantStore.PREFS_NAME, Context.MODE_PRIVATE))
                    .grantFor("com.example.trailbuddy"),
            ) { "the grant was not stored" }
            assertTrue(stored.read)
            assertFalse(stored.control)
        }
    }

    /** Backing out of the notice is a closed screen, not a grant and not an acknowledgement. */
    @Test
    fun closingTheNoticeGrantsNothing() {
        installCaller("com.example.trailbuddy")
        launch("com.example.trailbuddy").use {
            val activity = it.get()
            compose.onNodeWithText("Before you ride").assertIsDisplayed()
            activity.onBackPressedDispatcher.onBackPressed()

            assertTrue(activity.isFinishing)
            assertNull("backing out sets no result at all", shadowOf(activity).resultIntent)
            assertFalse(Prefs(app).safetyNoticeAcknowledged)
        }
    }

    /**
     * Mid-ride the rider is looking at the road, so the ride refusal comes
     * first and nothing is composed, the notice included. Read a failure here
     * as the notice having moved in front of the decider.
     */
    @Test
    fun aRideInProgressIsRefusedWithoutTheNotice() {
        installCaller("com.example.trailbuddy")
        RadarStateBus.publish(RadarState(source = DataSource.V2))
        launch("com.example.trailbuddy").use {
            val activity = it.get()
            assertTrue("it must not stay on screen", activity.isFinishing)
            assertEquals(Consent.RESULT_RIDE_IN_PROGRESS, shadowOf(activity).resultCode)
            assertNothingComposed(activity)
        }
    }

    /** An unnamed caller is refused before the notice too, and its refusal still carries both extras. */
    @Test
    fun anUnknownCallerIsRefusedWithoutTheNotice() {
        launch(null).use {
            val activity = it.get()
            val shadow = shadowOf(activity)
            assertTrue("it must not stay on screen", activity.isFinishing)
            assertEquals(Consent.RESULT_CALLER_UNKNOWN, shadow.resultCode)
            assertNothingComposed(activity)
            // Read off the intent directly, so an omitted extra fails here
            // rather than defaulting to false.
            val extras = requireNotNull(shadow.resultIntent.extras) { "a refusal must still carry extras" }
            assertFalse("no read grant", extras.getBoolean(Consent.EXTRA_READ, true))
            assertFalse("no control grant", extras.getBoolean(Consent.EXTRA_CONTROL, true))
        }
    }

    private fun assertNothingComposed(activity: Activity) {
        // Finishing and composing are independent, so a screen rendered and
        // then finished leaves the result code and isFinishing untouched.
        assertEquals(
            "nothing may be composed for a refused request",
            0,
            activity.findViewById<ViewGroup>(android.R.id.content).childCount,
        )
    }

    private fun launch(callingPackage: String?): ActivityController<RadarConsentActivity> {
        val controller = Robolectric.buildActivity(
            RadarConsentActivity::class.java,
            Intent(app, RadarConsentActivity::class.java),
        )
        shadowOf(controller.get()).setCallingPackage(callingPackage)
        return controller.setup()
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
        shadowOf(app.packageManager).installPackage(info)
        shadowOf(app.packageManager).setPackagesForUid(4_242, packageName)
    }
}
