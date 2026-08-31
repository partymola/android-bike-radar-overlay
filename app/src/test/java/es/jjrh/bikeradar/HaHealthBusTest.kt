// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the Home Assistant row is allowed to claim, and on what evidence.
 *
 * Three defects are pinned here, each of which let a status surface assert
 * something no observation supported:
 *  - every failure arrived as "publish failed", so a wrong token and an
 *    unreachable host were indistinguishable to the rider;
 *  - one global verdict meant a battery heartbeat succeeding could clear a
 *    ride-summary failure that nothing had retried;
 *  - nothing forgot an outcome when the credentials changed, so a verdict
 *    outlived the credentials that earned it in both directions.
 */
class HaHealthBusTest {

    // Both ends: the bus is a process-wide singleton shared with StateBusTest,
    // so resting-state assertions here are only meaningful if this class does
    // not inherit another's leftovers. Without the @Before, "no observations
    // at all is unknown" passes on worker ordering rather than on behaviour.
    @Before
    fun setUp() = HaHealthBus.reset()

    @After
    fun tearDown() = HaHealthBus.reset()

    // ── The cause classifier ──────────────────────────────────────────

    @Test
    fun `an auth failure is told apart from an unreachable host`() {
        // The two the rider acts on most differently: retype a token, or go
        // and look at whether the server is up.
        assertEquals(HaFailure.AUTH, classifyHaFailure(code = 401))
        assertEquals(HaFailure.AUTH, classifyHaFailure(code = 403))
        assertEquals(HaFailure.HOST_UNREACHABLE, classifyHaFailure(error = "UnknownHostException"))
        assertEquals(HaFailure.HOST_UNREACHABLE, classifyHaFailure(error = "SocketTimeoutException"))
        assertEquals(HaFailure.HOST_UNREACHABLE, classifyHaFailure(error = "ConnectException"))
    }

    @Test
    fun `a refused cleartext URL is its own cause`() {
        // Nothing left the app. Reporting this as unreachable would send the
        // rider to check a server that was never contacted.
        assertEquals(HaFailure.INSECURE_REFUSED, classifyHaFailure(insecureRefusal = true))
        // It outranks a code, because the refusal happens before the request.
        assertEquals(HaFailure.INSECURE_REFUSED, classifyHaFailure(code = 500, insecureRefusal = true))
    }

    @Test
    fun `server and not-found are distinct from both`() {
        assertEquals(HaFailure.SERVER_ERROR, classifyHaFailure(code = 500))
        assertEquals(HaFailure.SERVER_ERROR, classifyHaFailure(code = 503))
        assertEquals(HaFailure.NOT_FOUND, classifyHaFailure(code = 404))
    }

    @Test
    fun `an unrecognised failure stays unknown rather than guessing`() {
        // The rule the whole enum rests on: this classifies an observation.
        // Attributing an unrecognised failure to the most likely cause would
        // put a confident wrong answer on a screen.
        assertEquals(HaFailure.UNKNOWN, classifyHaFailure(code = 418))
        assertEquals(HaFailure.UNKNOWN, classifyHaFailure(error = "IllegalStateException"))
        assertEquals(HaFailure.UNKNOWN, classifyHaFailure())
    }

    // ── Aggregation across families ───────────────────────────────────

    @Test
    fun `a failing family is not cleared by a different one succeeding`() {
        // The families publish on different schedules, so a battery heartbeat
        // is no evidence the ride summary would now work.
        HaHealthBus.reportError(HaFamily.RIDE_SUMMARY, "summary failed", HaFailure.AUTH)
        HaHealthBus.reportOk(HaFamily.BATTERY)

        val state = HaHealthBus.state.value
        assertTrue("a live failure must survive another family's success, got $state", state is HaHealth.Error)
        assertEquals(HaFailure.AUTH, (state as HaHealth.Error).cause)
    }

    @Test
    fun `a family clears its own failure`() {
        // The other direction: the stream that failed is the one whose
        // success is evidence about it.
        HaHealthBus.reportError(HaFamily.BATTERY, "battery failed", HaFailure.HOST_UNREACHABLE)
        HaHealthBus.reportOk(HaFamily.BATTERY)

        assertEquals(HaHealth.Ok, HaHealthBus.state.value)
    }

    @Test
    fun `the newest failure is the one reported`() {
        // Only the newest has a current observation behind it.
        val old = HaHealth.Error("old", atMs = 1_000L, cause = HaFailure.NOT_FOUND)
        val new = HaHealth.Error("new", atMs = 2_000L, cause = HaFailure.AUTH)
        val agg = aggregateHaHealth(mapOf(HaFamily.BATTERY to old, HaFamily.RIDE_EDGE to new))
        assertEquals(HaFailure.AUTH, (agg as HaHealth.Error).cause)
    }

    @Test
    fun `no observations at all is unknown, not ok`() {
        // A fresh install has published nothing. Unknown is what the deriver
        // turns into CONFIGURED; Ok would render a working connection.
        assertEquals(HaHealth.Unknown, aggregateHaHealth(emptyMap()))
        assertEquals(HaHealth.Unknown, HaHealthBus.state.value)
    }

    @Test
    fun `each family keeps its own outcome for the bundle`() {
        // The aggregate can only say one thing; this is what names the topic
        // a maintainer should go and look at.
        HaHealthBus.reportOk(HaFamily.BATTERY)
        HaHealthBus.reportError(HaFamily.CLOSE_PASS, "close-pass failed", HaFailure.SERVER_ERROR)

        val fams = HaHealthBus.families.value
        assertEquals(HaHealth.Ok, fams[HaFamily.BATTERY])
        assertEquals(HaFailure.SERVER_ERROR, (fams[HaFamily.CLOSE_PASS] as HaHealth.Error).cause)
    }

    // ── Reset ─────────────────────────────────────────────────────────

    @Test
    fun `reset forgets every family, not just the aggregate`() {
        // A surviving per-family entry would re-poison the aggregate on the
        // next report from any other family.
        HaHealthBus.reportError(HaFamily.RIDE_EDGE, "failed", HaFailure.AUTH)
        HaHealthBus.reportOk(HaFamily.BATTERY)
        HaHealthBus.reset()

        assertEquals(HaHealth.Unknown, HaHealthBus.state.value)
        assertEquals(emptyMap<HaFamily, HaHealth>(), HaHealthBus.families.value)
    }
}
