// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.access.PrefsRadarGrantStore
import es.jjrh.bikeradar.access.RadarConsentActivity
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.ipc.RadarContract.Consent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A rider who has not tapped through the notice can reach no ACTIVITY of this
 * app.
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
class NoScreenBeforeTheNoticeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

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
            "a new activity is a new way into the app, and it must gate on " +
                "Prefs.safetyNoticeAcknowledged before it shows anything: $declared",
            setOf(MainActivity::class.java.name, RadarConsentActivity::class.java.name),
            declared,
        )
    }

    /**
     * The fixture where the gate is the only thing standing in the way.
     *
     * A caller the decider CAN name, and an install that has not acknowledged
     * the notice. Without the gate this reaches `ConsentRequest.Ask` and puts a
     * consent screen in front of a rider who has never seen the notice and
     * cannot reach it from there. Every other test here uses an unnameable
     * caller, which the decider refuses on its own, so the gate does no work in
     * any of them and a gate deleted from the `Ask` path alone survives them
     * all.
     *
     * The empty content view is the half that makes "without ever showing"
     * observable: finishing and composing are independent, so a screen rendered
     * and then finished leaves the result code and `isFinishing` untouched.
     *
     * The second half runs the SAME fixture with the flag set, and it is not a
     * courtesy. It is what proves the three assertions above can fail: it shows
     * the content view reports non-zero when a screen really composes, so the
     * count is an instrument rather than a constant. It also reds if this
     * fixture ever stops producing a caller the decider can name - a Robolectric
     * change, or an edit to `installCaller` - which would otherwise silently
     * turn this back into the unknown-caller test below, refused by the decider
     * with the gate doing nothing, and every assertion still green.
     */
    @Test
    fun anAppThisRiderCouldGrantIsStillRefusedBeforeTheTap() {
        installCaller("com.example.trailbuddy")
        val intent = Intent(app, RadarConsentActivity::class.java)
        Robolectric.buildActivity(RadarConsentActivity::class.java, intent).use { controller ->
            shadowOf(controller.get()).setCallingPackage("com.example.trailbuddy")
            val activity = controller.create().get()
            val shadow = shadowOf(activity)

            assertTrue("it must not stay on screen", activity.isFinishing)
            assertEquals(android.app.Activity.RESULT_CANCELED, shadow.resultCode)
            assertEquals(
                "nothing may be composed before the rider has seen the notice",
                0,
                activity.findViewById<ViewGroup>(android.R.id.content).childCount,
            )
        }

        Prefs(app).safetyNoticeAcknowledged = true
        Robolectric.buildActivity(RadarConsentActivity::class.java, intent).use { controller ->
            shadowOf(controller.get()).setCallingPackage("com.example.trailbuddy")
            val activity = controller.create().get()

            assertFalse(
                "this caller must be one the decider would have asked about, " +
                    "or the gate above was never what refused it",
                activity.isFinishing,
            )
            assertTrue(
                "the content view must be able to report a composed screen, " +
                    "or the count above cannot fail",
                activity.findViewById<ViewGroup>(android.R.id.content).childCount > 0,
            )
        }
    }

    /**
     * And the ride does not get it in first.
     *
     * The decider refuses a mid-ride request with its own code, so the two
     * refusals compete. The notice wins on purpose: an install whose rider has
     * never seen the notice has no business granting another app its radar,
     * whether or not a ride is under way. Read a failure here as the gate
     * having moved below the decider, not as this expectation being stale.
     */
    @Test
    fun theNoticeOutranksTheMidRideRefusal() {
        installCaller("com.example.trailbuddy")
        RadarStateBus.publish(RadarState(source = DataSource.V2))
        val intent = Intent(app, RadarConsentActivity::class.java)
        Robolectric.buildActivity(RadarConsentActivity::class.java, intent).use { controller ->
            shadowOf(controller.get()).setCallingPackage("com.example.trailbuddy")
            val activity = controller.create().get()

            // Both assertions, because the code alone would not discriminate:
            // Robolectric reports RESULT_CANCELED for an activity that never
            // called setResult, so a mutant that composed instead of refusing
            // would read 0 and pass.
            assertTrue("it must not stay on screen", activity.isFinishing)
            assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        }
    }

    /**
     * What a refusal carries back, with no caller to name either.
     *
     * The decider would refuse this one anyway, so the gate is not what stops
     * it and the name does not claim otherwise: what this adds over the test
     * above is the grant extras, which must both be present and false rather
     * than omitted.
     */
    @Test
    fun aRefusalBeforeTheTapCarriesNoGrant() {
        val intent = Intent(app, RadarConsentActivity::class.java)
        Robolectric.buildActivity(RadarConsentActivity::class.java, intent).use { controller ->
            val activity = controller.create().get()
            val shadow = shadowOf(activity)

            assertTrue("it must not stay on screen", activity.isFinishing)
            assertEquals(android.app.Activity.RESULT_CANCELED, shadow.resultCode)
            // Read off the intent directly. Defaulting a missing extra to false
            // would pass a mutant that never calls setResult at all, since
            // RESULT_CANCELED is also what Robolectric reports for one.
            val extras = requireNotNull(shadow.resultIntent.extras) { "a refusal must still carry extras" }
            assertFalse("no read grant", extras.getBoolean(Consent.EXTRA_READ))
            assertFalse("no control grant", extras.getBoolean(Consent.EXTRA_CONTROL))
        }
    }

    /** And it stops refusing once the rider has tapped through. */
    @Test
    fun theConsentScreenWorksNormallyAfterTheTap() {
        Prefs(app).safetyNoticeAcknowledged = true
        val intent = Intent(app, RadarConsentActivity::class.java)
        Robolectric.buildActivity(RadarConsentActivity::class.java, intent).use { controller ->
            val activity = controller.create().get()
            // With no calling package Robolectric gives it an unknown caller,
            // which the decider refuses with its OWN code. Reaching that code
            // is the proof the notice gate is no longer what stopped it.
            assertEquals(
                Consent.RESULT_CALLER_UNKNOWN,
                shadowOf(activity).resultCode,
            )
        }
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
