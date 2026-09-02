// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.ipc.RadarOverlayGate
import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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
    fun theRideNotificationIsRepostedWhenAHoldChanges() {
        // The wiring, not the wording. `buildForeground()` carrying the line
        // says nothing about a rider ever seeing it: the ongoing notification
        // is static between posts, so without a collector pushing one the line
        // appears only when something unrelated happens to repost, which
        // mid-ride is nothing at all.
        //
        // Read from the source because the collector lives inside the ride
        // service's onCreate, which a unit test cannot stand up. So this pins
        // that the wiring EXISTS and is shaped right; that it fires is a
        // property of StateFlow, and what it posts is pinned above.
        // Comments stripped, like the sibling check in
        // `RadarIpcServiceCollectorsSurviveTest`: without it a comment that
        // happens to use one of these words satisfies the assertion.
        val code = RepoFiles.mainSource("BikeRadarService.kt").readText().lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        val launch = Regex("""scope\.launch \{\s*RadarOverlayGate\.hiddenBy(.*?)\n {8}\}""", RegexOption.DOT_MATCHES_ALL)
            .find(code)

        assertTrue("nothing reposts the notification when a hold changes", launch != null)
        val body = launch!!.groupValues[1]
        assertTrue("the repost is what makes the line reach a rider: $body", body.contains("postForeground()"))
        assertTrue(
            "a throw here would end the collector for good and take the explanation with it: $body",
            body.contains("catch (t: Throwable)"),
        )
        // Narrowing to Exception is the cheap way to write this and the wrong
        // one - an Error ends the collector just as permanently. The two
        // cross-app collectors have a behavioural test for exactly that; this
        // one can only read for it.
        assertTrue(
            "cancellation has to keep propagating, or this runs on against a stopped service: $body",
            body.contains("CancellationException"),
        )
        assertTrue(
            "drop(1) keeps start-up from posting twice, since startForeground has just rendered it: $body",
            body.contains("drop(1)"),
        )
    }
}
