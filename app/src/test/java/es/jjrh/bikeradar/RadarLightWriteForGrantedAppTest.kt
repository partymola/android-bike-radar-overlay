// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import es.jjrh.bikeradar.testutil.RepoFiles
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one path in the app that writes to the rider's hardware because another
 * app asked.
 *
 * It runs on a binder thread the consumer is blocking, outside any scope that
 * gets cancelled when the link tears down, so every way out of it matters: the
 * mode has to arrive unchanged, a wedged write has to end rather than hold the
 * thread for ever, and nothing may cross the boundary as an exception where the
 * contract promises false.
 *
 * Against a lambda rather than a real `RadarLightController`, because a
 * controller needs a live GATT and a real queue and neither can be made to
 * hang or throw on demand. What is under test is what this function does with
 * an answer, not how the answer is produced.
 */
@RunWith(RobolectricTestRunner::class)
class RadarLightWriteForGrantedAppTest {

    @Test
    fun theRequestedModeIsWhatReachesTheRadar() {
        // The mutant this exists for: ignore `mode` and write a constant. A
        // rider who asked their navigation app for a steady light gets whatever
        // that constant is, and every other test still passes.
        for (wanted in RadarLightMode.entries) {
            var seen: RadarLightMode? = null
            val ok = RadarLinkController.writeLightForGrantedApp({
                seen = it
                true
            }, wanted, TIMEOUT)

            assertTrue(ok)
            assertEquals("the mode must cross unchanged", wanted, seen)
        }
    }

    @Test
    fun aRefusedWriteIsReportedAsRefused() {
        assertFalse(RadarLinkController.writeLightForGrantedApp({ false }, RadarLightMode.SOLID, TIMEOUT))
    }

    @Test
    fun aWriteThatNeverAnswersEndsRatherThanHoldingTheThread() {
        // Without the timeout this suspends for ever on a binder pool thread.
        // The process has about sixteen; enough of them wedged and the app
        // answers no transactions at all, including the rider's own.
        val start = System.currentTimeMillis()

        val answer = RadarLinkController.writeLightForGrantedApp({
            delay(60_000)
            true
        }, RadarLightMode.OFF, TIMEOUT)

        assertFalse("giving up has to read as failure, not success", answer)
        assertTrue("it must give up near the timeout", System.currentTimeMillis() - start < 5_000)
    }

    @Test
    fun aThrowBecomesFalseRatherThanCrossingTheBoundary() {
        // `runBlocking` rethrows on the binder thread, which the consumer sees
        // as an exception where the AIDL documents a boolean.
        assertFalse(
            RadarLinkController.writeLightForGrantedApp({ throw IllegalStateException("gatt gone") }, RadarLightMode.PELOTON, TIMEOUT),
        )
    }

    @Test
    fun theBridgeCeilingSitsAboveTheQueuesOwnPerOpTimeout() {
        // A relation between two constants, not a constant against itself.
        // Below the queue's timeout, every write that reaches the radio and
        // answers slowly is reported to the consumer as a failure, and nothing
        // else in the suite would notice.
        assertTrue(
            "the bridge must outlast one queued op, or a healthy write reads as failed",
            RadarLinkController.BRIDGE_WRITE_TIMEOUT_MS > BleOpQueue.DEFAULT_TIMEOUT_MS,
        )
        // The upper bound matters as much and was the unpinned half: at 25 s
        // every test here still passed while the KDoc's reasoning about which
        // ANR windows this stays under became false. A consumer calling from a
        // broadcast receiver would be killed by the system.
        assertTrue(
            "the ceiling has to stay under the 10 s broadcast window the KDoc claims",
            RadarLinkController.BRIDGE_WRITE_TIMEOUT_MS < 10_000L,
        )
    }

    @Test
    fun aGrantedAppsRequestedModeSurvivesTheInstallSite() {
        // The seam, not the function behind it. Everything above could be
        // correct while the one call expression that installs it passed a
        // constant mode, a different timeout, or a lambda ignoring its
        // argument, and no test here would notice.
        //
        // Read from the source rather than driven. The bridge blocks its caller
        // with `runBlocking`, and the harness completes BLE writes on the test
        // scheduler, so an in-place call would wait on a scheduler that is
        // waiting on it. That is a limit of this pin: it checks the call is
        // shaped right, never that a byte reached the radio.
        val text = RepoFiles.mainSource("RadarLinkController.kt").readText()

        val install = Regex("""RadarControlBridge\.install \{ mode ->(.*?)\n {16}\}""", RegexOption.DOT_MATCHES_ALL)
            .find(text)
        assertTrue("nothing installs a tail-light handler any more", install != null)

        val body = install!!.groupValues[1]
        // Named, because it is the function that catches. Without this the
        // three shape checks below all pass over an inlined rewrite that drops
        // the `runCatching`, leaving the tested function dead code with its own
        // throw test still green, and letting a GATT throw cross the bridge to
        // a third-party consumer where the contract promises a boolean.
        assertTrue("the handler has to go through the function that catches: $body", body.contains("writeLightForGrantedApp"))
        assertTrue("the handler has to reach the live light: $body", body.contains("light::setMode"))
        assertTrue("and pass the mode it was asked for: $body", body.contains(", mode,"))
        assertTrue("and the ceiling this file reasons about: $body", body.contains("BRIDGE_WRITE_TIMEOUT_MS"))
    }

    private companion object {
        /** Short, so the give-up test takes a moment rather than six seconds. */
        const val TIMEOUT = 300L
    }
}
