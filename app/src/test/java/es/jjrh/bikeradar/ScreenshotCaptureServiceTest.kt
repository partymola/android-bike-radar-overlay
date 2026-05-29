// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Intent
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Proxy contract test for [ScreenshotCaptureService]'s start/stop ordering
 * (audit finding M11).
 *
 * On API 31-33 a service started via startForegroundService() MUST call
 * startForeground() within ~5 s or the framework throws
 * ForegroundServiceDidNotStartInTimeException. Every early-exit path in
 * onStartCommand that runs BEFORE beginProjection (STOP, invalid/missing
 * extras, unknown action) used to call stopSelf() without ever entering
 * foreground - a latent crash on those API levels.
 *
 * Robolectric does NOT enforce the real FGS timer, so these tests cannot
 * reproduce the crash. Instead they pin the CONTRACT that prevents it: each
 * early-exit path must enter THEN exit foreground (startForeground() followed
 * by stopForeground()) and stop itself. We assert via `isForegroundStopped`
 * because the helper removes the notification with STOP_FOREGROUND_REMOVE
 * (which the shadow clears), so the notification object itself is not a
 * reliable signal. Crucially this discriminates fixed from broken: before the
 * fix these paths did a bare stopSelf() with no foreground lifecycle at all,
 * so `isForegroundStopped` is false and the assertions fail.
 *
 * The valid-projection happy path is intentionally not covered here: it needs
 * a shadowed MediaProjection, which is out of scope for this contract fence.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenshotCaptureServiceTest {

    @Test
    fun startWithMissingExtrasEntersForegroundThenStops() {
        // ACTION_START with no result extras: resultCode defaults to 0 and
        // resultData is null, so onStartCommand bails before beginProjection.
        val intent = Intent().apply { action = ScreenshotCaptureService.ACTION_START }
        val controller = Robolectric.buildService(ScreenshotCaptureService::class.java, intent)
        val service = controller.create().startCommand(0, 1).get()

        val shadow = shadowOf(service)
        assertTrue(
            "missing-extras path must enter then exit foreground (not bare stopSelf) to satisfy the FGS timer",
            shadow.isForegroundStopped,
        )
        assertTrue(
            "missing-extras path must stop the service after foregrounding",
            shadow.isStoppedBySelf,
        )
    }

    @Test
    fun stopActionEntersForegroundThenStops() {
        // A bare ACTION_STOP delivered via startForegroundService() still has
        // to satisfy the platform timer before tearing down.
        val intent = Intent().apply { action = ScreenshotCaptureService.ACTION_STOP }
        val controller = Robolectric.buildService(ScreenshotCaptureService::class.java, intent)
        val service = controller.create().startCommand(0, 1).get()

        val shadow = shadowOf(service)
        assertTrue(
            "STOP path must enter then exit foreground (not bare stopSelf) to satisfy the FGS timer",
            shadow.isForegroundStopped,
        )
        assertTrue(
            "STOP path must stop the service after foregrounding",
            shadow.isStoppedBySelf,
        )
    }

    @Test
    fun unknownActionEntersForegroundThenStops() {
        // An unrecognised (or null) action falls through to the else branch,
        // which must also foreground-then-stop rather than bare stopSelf().
        val intent = Intent().apply { action = "es.jjrh.bikeradar.NOT_A_REAL_ACTION" }
        val controller = Robolectric.buildService(ScreenshotCaptureService::class.java, intent)
        val service = controller.create().startCommand(0, 1).get()

        val shadow = shadowOf(service)
        assertTrue(
            "unknown-action path must enter then exit foreground (not bare stopSelf) to satisfy the FGS timer",
            shadow.isForegroundStopped,
        )
        assertTrue(
            "unknown-action path must stop the service after foregrounding",
            shadow.isStoppedBySelf,
        )
    }
}
