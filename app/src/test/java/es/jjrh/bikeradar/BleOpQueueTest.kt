// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the submit-retry contract of [BleOpQueue]. The Android GATT submit
 * calls transiently return false when the stack is briefly busy; retrySubmit
 * tolerates that with a bounded number of retries before giving up. The retry
 * count and "give up after N" boundary are the subtle, safety-relevant bits
 * (a too-eager give-up drops a handshake op), so they are pinned here. The
 * delay between attempts is skipped by runTest's virtual clock.
 */
class BleOpQueueTest {

    @Test fun succeedsOnFirstAttempt_noRetry() = runTest {
        var calls = 0
        val ok = BleOpQueue().retrySubmit {
            calls++
            true
        }
        assertTrue(ok)
        assertEquals("a first-try success must not retry", 1, calls)
    }

    @Test fun retriesUntilSuccess() = runTest {
        var calls = 0
        val ok = BleOpQueue().retrySubmit {
            calls++
            calls >= 3
        }
        assertTrue(ok)
        assertEquals(3, calls)
    }

    @Test fun succeedsOnFinalAttempt() = runTest {
        var calls = 0
        // SUBMIT_RETRY_ATTEMPTS is 5; success on the 5th must still count.
        val ok = BleOpQueue().retrySubmit {
            calls++
            calls == 5
        }
        assertTrue(ok)
        assertEquals(5, calls)
    }

    @Test fun givesUpAfterFiveAttempts() = runTest {
        var calls = 0
        val ok = BleOpQueue().retrySubmit {
            calls++
            false
        }
        assertFalse("a never-succeeding submit must report failure", ok)
        assertEquals("must stop at SUBMIT_RETRY_ATTEMPTS, not loop forever", 5, calls)
    }
}
