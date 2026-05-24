// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-flow contract for the small process-wide buses that feed the home
 * screen and the HA-health chip: [HaHealthBus] and [ClosePassStateBus].
 * Pure MutableStateFlow holders, no Android dependency. Both are singletons,
 * so each test sets the state it asserts and the close-pass count is reset
 * in tearDown to avoid leaking into a later test.
 */
class StateBusTest {

    @After
    fun tearDown() {
        ClosePassStateBus.reset()
    }

    @Test
    fun haHealthBusReportsOk() {
        HaHealthBus.reportOk()
        assertEquals(HaHealth.Ok, HaHealthBus.state.value)
    }

    @Test
    fun haHealthBusReportsErrorWithMessageAndTimestamp() {
        HaHealthBus.reportError("mqtt down")
        val s = HaHealthBus.state.value
        assertTrue(s is HaHealth.Error)
        s as HaHealth.Error
        assertEquals("mqtt down", s.message)
        assertTrue("Error timestamp should be wall-clock", s.atMs > 0L)
    }

    @Test
    fun haHealthErrorEqualityTracksMessageAndTimestamp() {
        // atMs is part of the data class, so two Errors with the same message
        // but different timestamps are distinct (the chip can tell a fresh
        // failure from a stale one).
        val a = HaHealth.Error("x", atMs = 100L)
        val b = HaHealth.Error("x", atMs = 100L)
        val c = HaHealth.Error("x", atMs = 200L)
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun closePassBusIncrementsByOneByDefault() {
        ClosePassStateBus.reset()
        ClosePassStateBus.increment()
        ClosePassStateBus.increment()
        assertEquals(2, ClosePassStateBus.sessionCount.value)
    }

    @Test
    fun closePassBusIncrementsByAnExplicitAmount() {
        ClosePassStateBus.reset()
        ClosePassStateBus.increment(3)
        assertEquals(3, ClosePassStateBus.sessionCount.value)
    }

    @Test
    fun closePassBusResetZeroesTheSessionCount() {
        ClosePassStateBus.increment(5)
        ClosePassStateBus.reset()
        assertEquals(0, ClosePassStateBus.sessionCount.value)
    }
}
