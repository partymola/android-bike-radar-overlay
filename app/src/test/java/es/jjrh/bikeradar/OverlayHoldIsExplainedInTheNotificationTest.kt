// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.ipc.RadarOverlayGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.concurrent.thread

/**
 * A rider whose overlay disappears has to be able to find out why.
 *
 * A granted app can ask for the collision-warning display to make way for its
 * own. When it does, the screen simply stops showing a radar - so without this
 * line the answer to "where did it go" is on no surface of the phone at all:
 * not the notification, not Settings, only a capture-log entry that is off by
 * default.
 *
 * The ongoing notification is where it goes because it is the one surface a
 * rider already looks at mid-ride. Named rather than counted, because "an app
 * is using your screen" is not something anyone can act on.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayHoldIsExplainedInTheNotificationTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val notifications = ServiceNotifications(app) { Prefs(app) }

    private fun subText(): String? = notifications.buildForeground().extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

    @Before fun clean() = RadarOverlayGate.reset()

    @After fun cleanUp() = RadarOverlayGate.reset()

    @Test
    fun nothingIsSaidWhileTheOverlayIsTheRidersOwn() {
        // The ordinary ride. A line here every time would train the rider to
        // stop reading it, which is what makes the held case invisible.
        assertNull(subText())
    }

    @Test
    fun aHeldOverlayNamesTheAppHoldingIt() {
        RadarOverlayGate.hide("com.example.trailbuddy")

        val text = subText()

        assertTrue("nothing on the notification explains the missing overlay: $text", text != null)
        assertTrue(
            "the rider cannot act on a line that does not say which app: $text",
            text!!.contains("com.example.trailbuddy"),
        )
    }

    @Test
    fun theLineGoesWhenTheHoldIsLifted() {
        RadarOverlayGate.hide("com.example.trailbuddy")
        assertTrue(subText() != null)

        RadarOverlayGate.show("com.example.trailbuddy")

        assertNull("a stale line would send the rider hunting a hold that has gone", subText())
    }

    @Test
    fun aSecondHolderIsCountedRatherThanNamed() {
        // Joining both names overflows the header slot Android gives this, and
        // in Spanish the truncation eats the name and leaves the prefix. So one
        // name and a count: the rider still learns that revoking the app they
        // can see will not be enough on its own.
        //
        // Sorted, so the name does not reshuffle between posts.
        RadarOverlayGate.hide("com.example.zulu")
        RadarOverlayGate.hide("com.example.alpha")

        assertEquals("Overlay hidden: com.example.alpha +1", subText())
    }

    @Test
    fun anUninstalledHolderStillGetsNamed() {
        // The package name is the fallback when there is no label to look up -
        // an app uninstalled while holding, say. Silently dropping the name
        // would leave the rider with a line that explains nothing.
        RadarOverlayGate.hide("com.example.gone")

        assertEquals("Overlay hidden: com.example.gone", subText())
    }

    @Test
    fun theRidersOwnWordForTheAppIsPreferredToItsPackage() {
        // What the rider saw on the consent screen was the label, so that is
        // what the notification has to echo; a package name sends them looking
        // for something they have never seen. Every other test here uses an
        // app that is not installed, so without this one the whole lookup could
        // be replaced by the fallback and nothing would notice.
        val info = ApplicationInfo().apply {
            packageName = "com.example.trailbuddy"
            nonLocalizedLabel = "Trail Buddy"
        }
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                packageName = "com.example.trailbuddy"
                applicationInfo = info
            },
        )
        RadarOverlayGate.hide("com.example.trailbuddy")

        assertEquals("Overlay hidden: Trail Buddy", subText())
    }

    @Test
    fun aCallSaysNothingBecauseTheOverlayIsShowing() {
        // During a call the pipeline shows the overlay over the hold, so a line
        // saying it is hidden would describe a screen the rider is not seeing.
        RadarOverlayGate.hide("com.example.trailbuddy")
        val audio = app.getSystemService(AudioManager::class.java)

        audio.mode = AudioManager.MODE_RINGTONE
        assertEquals("a ringing phone is not a call yet", "Overlay hidden: com.example.trailbuddy", subText())

        audio.mode = AudioManager.MODE_CALL_SCREENING
        assertEquals("a call being screened is not answered", "Overlay hidden: com.example.trailbuddy", subText())

        audio.mode = AudioManager.MODE_IN_CALL
        assertNull("a phone call", subText())

        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        assertNull("an internet call", subText())

        audio.mode = AudioManager.MODE_NORMAL
        assertEquals("the hold applies again after the call", "Overlay hidden: com.example.trailbuddy", subText())
    }

    private val nm get() = app.getSystemService(NotificationManager::class.java)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The posted line. Fails when nothing is posted at all, so a missing
     *  notification cannot pass for a missing line. */
    private fun postedSubText(): String? {
        val posted = shadowOf(nm).getNotification(ServiceNotifications.NOTIF_ID)
        assertNotNull("no ride notification is posted", posted)
        return posted!!.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
    }

    /** Runs [body] with reposts launched on a scope it cancels afterwards. */
    private fun withReposts(target: ServiceNotifications = notifications, body: () -> Unit) {
        val scope = CoroutineScope(SupervisorJob())
        target.launchReposts(scope)
        try {
            body()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aHoldChangingRepostsTheLine() {
        // The notification is static between posts. The state at subscription
        // is posted too, or a hold landing before it would go unexplained.
        RadarOverlayGate.hide("com.example.trailbuddy")
        idle()
        nm.cancel(ServiceNotifications.NOTIF_ID)
        withReposts {
            idle()
            assertEquals("Overlay hidden: com.example.trailbuddy", postedSubText())

            RadarOverlayGate.show("com.example.trailbuddy")
            idle()
            assertNull("a stale line would send the rider hunting a hold that has gone", postedSubText())
        }
    }

    @Test
    fun aCallStartingOrEndingRepostsTheLine() {
        val audio = app.getSystemService(AudioManager::class.java)
        RadarOverlayGate.hide("com.example.trailbuddy")
        withReposts {
            idle()
            audio.mode = AudioManager.MODE_IN_CALL
            idle()
            assertNull("the posted line still says hidden during the call", postedSubText())

            audio.mode = AudioManager.MODE_NORMAL
            idle()
            assertEquals("Overlay hidden: com.example.trailbuddy", postedSubText())
        }
    }

    @Test
    fun repostsAreBuiltOnTheMainThread() {
        // One thread for every repost, so a hold change and a call edge landing
        // together cannot post out of order. The hold comes from another
        // thread, as a consumer's does over binder, so a collector that merely
        // runs wherever it is resumed records false.
        val onMain = mutableListOf<Boolean>()
        val recording = ServiceNotifications(app) {
            synchronized(onMain) { onMain.add(Looper.myLooper() == Looper.getMainLooper()) }
            Prefs(app)
        }
        withReposts(recording) {
            idle()
            thread { RadarOverlayGate.hide("com.example.trailbuddy") }.join()
            idle()
        }
        synchronized(onMain) {
            assertEquals("a repost for the start and one for the hold: $onMain", 2, onMain.size)
            assertTrue("a repost was built off the main thread: $onMain", onMain.all { it })
        }
    }

    @Test
    fun aCancelledScopePostsNothingEvenForAnEdgeAlreadyQueued() {
        // The service's onDestroy cancels the scope. A post after it would put
        // the ongoing notification back for a service that is gone.
        val audio = app.getSystemService(AudioManager::class.java)
        val scope = CoroutineScope(SupervisorJob())
        notifications.launchReposts(scope)
        idle()
        nm.cancel(ServiceNotifications.NOTIF_ID)

        audio.mode = AudioManager.MODE_IN_CALL
        scope.cancel()
        idle()
        assertNull(shadowOf(nm).getNotification(ServiceNotifications.NOTIF_ID))

        // The listener is gone too: a later edge queues nothing at all. Left
        // registered, it would hold the stopped service's context.
        audio.mode = AudioManager.MODE_NORMAL
        assertTrue("the mode listener outlived the scope", shadowOf(Looper.getMainLooper()).isIdle)
    }

    @Test
    fun aFailedRepostLeavesTheCollectorRunning() {
        // An Error rather than an Exception, since either would end the
        // collector, and the line with it, for the rest of the ride.
        var failing = true
        var failures = 0
        val flaky = ServiceNotifications(app) {
            if (failing) {
                failures++
                throw NoClassDefFoundError("boom")
            }
            Prefs(app)
        }
        withReposts(flaky) {
            idle()
            assertEquals("the first repost must have failed", 1, failures)
            failing = false
            RadarOverlayGate.hide("com.example.trailbuddy")
            idle()
            assertEquals("Overlay hidden: com.example.trailbuddy", postedSubText())
        }
    }
}
