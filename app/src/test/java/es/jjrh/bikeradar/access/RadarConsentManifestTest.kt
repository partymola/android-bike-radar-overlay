// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.ipc.RadarContract.Consent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The action a consumer app sends and the action the manifest answers are the
 * same string in two files, and a consumer copies it rather than importing it.
 * Nothing else would notice them drifting apart.
 */
@RunWith(RobolectricTestRunner::class)
class RadarConsentManifestTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theDeclaredActionIsTheOneAConsumerSends() {
        val resolved = context.packageManager.resolveActivity(Intent(Consent.ACTION), 0)
        assertNotNull(
            "no activity answers ${Consent.ACTION}, so a consumer's request goes nowhere",
            resolved,
        )
        assertEquals(
            RadarConsentActivity::class.java.name,
            resolved!!.activityInfo.name,
        )
    }

    @Test
    fun theConsentScreenRunsInTheCallersTask() {
        // The whole contract is getCallingPackage(), which reads the result
        // link. A launch mode that forces its own task breaks that link: the
        // caller is sent RESULT_CANCELED before the activity runs and the name
        // arrives null, so every consumer is refused and nothing can ever be
        // granted. No unit test can see it, because the shadow sets the calling
        // package directly and bypasses the task machinery.
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, RadarConsentActivity::class.java),
            0,
        )
        assertEquals(
            "a non-default launch mode would break startActivityForResult",
            android.content.pm.ActivityInfo.LAUNCH_MULTIPLE,
            info.launchMode,
        )
    }

    @Test
    fun theConsentScreenIsReachableFromOutsideThisApp() {
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, RadarConsentActivity::class.java),
            0,
        )
        assertTrue("a consumer in another process must be able to start it", info.exported)
    }
}
