// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [RideWakeLock]: a capped acquire holds the lock, release frees it, and
 * both are idempotent so the several release call sites (reconnect, walk-away
 * BLANK, ride summary, onDestroy) can each fire safely.
 */
@RunWith(RobolectricTestRunner::class)
class RideWakeLockTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun acquireHoldsAndReleaseFrees() {
        val wl = RideWakeLock(app)
        assertFalse(wl.isHeld())
        wl.acquire(300_000L)
        assertTrue(wl.isHeld())
        wl.release()
        assertFalse(wl.isHeld())
    }

    @Test
    fun acquireIsIdempotentAndReleaseFreesOnce() {
        // Not reference-counted: a second acquire is a no-op, so a single release
        // frees the lock (a ref-counted lock would still be held here).
        val wl = RideWakeLock(app)
        wl.acquire(300_000L)
        wl.acquire(300_000L)
        assertTrue(wl.isHeld())
        wl.release()
        assertFalse(wl.isHeld())
    }

    @Test
    fun releaseWhenNotHeldIsNoOp() {
        val wl = RideWakeLock(app)
        wl.release() // must not throw
        assertFalse(wl.isHeld())
    }
}
