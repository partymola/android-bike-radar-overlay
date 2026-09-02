// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * A phone with no browser is a real configuration, and every caller here is a
 * link the rider followed from a legal screen. Unguarded, that phone gets an
 * `ActivityNotFoundException` and the app disappears out from under them.
 */
@RunWith(RobolectricTestRunner::class)
class OpenLinkTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun stopCheckingActivities() {
        shadowOf(app).checkActivities(false)
    }

    @Test
    fun theAddressIsWhatGetsOpened() {
        openLink(app, "https://example.invalid/licence")

        val started = shadowOf(app).nextStartedActivity
        assertNotNull("nothing was launched", started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.invalid/licence", started.data.toString())
        // Without NEW_TASK a non-Activity context throws AndroidRuntimeException,
        // which the catch below does not cover, so the licence screen would take
        // the app down. Robolectric's shadow does not enforce that rule, so only
        // this assertion stands between the flag and its silent removal.
        assertTrue(
            "the launch must carry FLAG_ACTIVITY_NEW_TASK",
            started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun aPhoneWithNothingToOpenItSaysSo() {
        // Robolectric raises the same ActivityNotFoundException the framework
        // does once it is told to resolve intents for real.
        shadowOf(app).checkActivities(true)

        openLink(app, "https://example.invalid/licence")

        // Surviving is half of it. Emptying the catch body also survives, and
        // the rider then taps a licence row and gets nothing at all.
        assertEquals(
            app.getString(R.string.link_no_browser),
            ShadowToast.getTextOfLatestToast(),
        )
    }
}
