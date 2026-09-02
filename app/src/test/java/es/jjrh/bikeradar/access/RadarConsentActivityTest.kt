// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.ipc.RadarContract.Consent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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

    @Before
    fun clearGrants() {
        context.getSharedPreferences(PrefsRadarGrantStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
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
        val activity = launch("com.example.trailbuddy")
        assertFalse(
            "the rider has not answered yet, so nothing may be returned",
            activity.isFinishing,
        )
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }
}
