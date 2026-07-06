// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BornCloseGateTest {

    private fun bornClose(
        id: Int = 7,
        distanceM: Int = 6,
        speedMs: Float = -0.5f,
        bornDistanceM: Int = 6,
        bornInformative: Boolean = true,
        bornAtMs: Long = 1_000L,
    ) = Vehicle(
        id = id,
        distanceM = distanceM,
        speedMs = speedMs,
        bornDistanceM = bornDistanceM,
        bornInformative = bornInformative,
        bornAtMs = bornAtMs,
    )

    private val idle = TurnStateDecider.State.IDLE
    private val turning = TurnStateDecider.State.TURNING
    private val hold = TurnStateDecider.State.HOLD

    @Test
    fun `born-close low-closing track stays gated`() {
        val g = BornCloseGate()
        var now = 0L
        repeat(30) {
            g.update(listOf(bornClose(speedMs = -0.5f)), idle, now)
            now += 90
        }
        assertTrue(g.isGated(bornClose()))
    }

    @Test
    fun `default Vehicle is never gated - synthetic sources fail open`() {
        val g = BornCloseGate()
        val synthetic = Vehicle(id = 1, distanceM = 5, speedMs = 0f)
        g.update(listOf(synthetic), idle, 0L)
        assertFalse(g.isGated(synthetic))
    }

    @Test
    fun `born far is never gated`() {
        val g = BornCloseGate()
        val v = bornClose(bornDistanceM = 30)
        g.update(listOf(v), idle, 0L)
        assertFalse(g.isGated(v))
    }

    @Test
    fun `uninformative birth is never gated - coverage-gap reacquisition`() {
        val g = BornCloseGate()
        val v = bornClose(bornInformative = false)
        g.update(listOf(v), idle, 0L)
        assertFalse(g.isGated(v))
    }

    @Test
    fun `fast path admits after two clean closing frames`() {
        val g = BornCloseGate()
        val v = bornClose(speedMs = -2.5f)
        g.update(listOf(v), idle, 0L)
        assertTrue(g.isGated(v))
        g.update(listOf(v), idle, 90L)
        assertFalse(g.isGated(v))
    }

    @Test
    fun `fast-path evidence does not count while TURNING`() {
        val g = BornCloseGate()
        val v = bornClose(speedMs = -3f)
        repeat(30) { g.update(listOf(v), turning, it * 90L) }
        assertTrue(g.isGated(v))
    }

    @Test
    fun `HOLD counts as clean for the fast path`() {
        val g = BornCloseGate()
        val v = bornClose(speedMs = -2.5f)
        g.update(listOf(v), hold, 0L)
        g.update(listOf(v), hold, 90L)
        assertFalse(g.isGated(v))
    }

    @Test
    fun `urgent-grade closing admits even while TURNING`() {
        val g = BornCloseGate()
        val v = bornClose(speedMs = -6f)
        g.update(listOf(v), turning, 0L)
        g.update(listOf(v), turning, 90L)
        assertFalse(g.isGated(v))
    }

    @Test
    fun `slow path admits after sustained gentle closing`() {
        val g = BornCloseGate()
        val v = bornClose(speedMs = -1f)
        var now = 0L
        repeat(16) {
            g.update(listOf(v), idle, now)
            now += 100
        }
        assertFalse(g.isGated(v))
    }

    @Test
    fun `slow-path run is broken by a non-closing frame`() {
        val g = BornCloseGate()
        var now = 0L
        repeat(12) {
            g.update(listOf(bornClose(speedMs = -1f)), idle, now)
            now += 100
        }
        g.update(listOf(bornClose(speedMs = 0f)), idle, now) // run breaks at 1200 ms
        now += 100
        repeat(12) {
            g.update(listOf(bornClose(speedMs = -1f)), idle, now)
            now += 100
        }
        // 1100 ms accrued post-break: still short of the 1500 ms dwell.
        assertTrue(g.isGated(bornClose()))
    }

    @Test
    fun `single closing frame between idle frames does not admit`() {
        val g = BornCloseGate()
        g.update(listOf(bornClose(speedMs = -3f)), idle, 0L)
        g.update(listOf(bornClose(speedMs = -0.5f)), idle, 90L)
        g.update(listOf(bornClose(speedMs = -3f)), idle, 180L)
        assertTrue(g.isGated(bornClose()))
    }

    @Test
    fun `admission re-fires a previously suppressed cue exactly once`() {
        val g = BornCloseGate()
        val slow = bornClose(speedMs = -0.5f)
        g.update(listOf(slow), idle, 0L)
        g.noteSuppressed(slow)
        val fast = bornClose(speedMs = -2.5f)
        assertEquals(emptyList<Int>(), g.update(listOf(fast), idle, 90L))
        assertEquals(listOf(7), g.update(listOf(fast), idle, 180L))
        assertEquals(emptyList<Int>(), g.update(listOf(fast), idle, 270L))
    }

    @Test
    fun `onClear drops the pending re-fire but keeps admission`() {
        val g = BornCloseGate()
        val slow = bornClose(speedMs = -0.5f)
        g.update(listOf(slow), idle, 0L)
        g.noteSuppressed(slow)
        g.onClear()
        val fast = bornClose(speedMs = -2.5f)
        g.update(listOf(fast), idle, 90L)
        assertEquals(emptyList<Int>(), g.update(listOf(fast), idle, 180L))
        assertFalse(g.isGated(fast))
    }

    @Test
    fun `tid reuse does not inherit admission - keyed by birth time`() {
        val g = BornCloseGate()
        val first = bornClose(bornAtMs = 1_000L, speedMs = -2.5f)
        g.update(listOf(first), idle, 0L)
        g.update(listOf(first), idle, 90L)
        assertFalse(g.isGated(first))
        val reborn = bornClose(bornAtMs = 9_000L, speedMs = -0.5f)
        g.update(listOf(reborn), idle, 180L)
        assertTrue(g.isGated(reborn))
    }

    @Test
    fun `state expires after the track vanishes`() {
        val g = BornCloseGate()
        val v = bornClose(speedMs = -2.5f)
        g.update(listOf(v), idle, 0L)
        g.update(listOf(v), idle, 90L)
        assertFalse(g.isGated(v))
        g.update(emptyList(), idle, 3_000L) // stale prune
        // Unknown state for an applicable track defaults to gated.
        assertTrue(g.isGated(v))
    }

    @Test
    fun `born exactly at the close boundary is gated`() {
        val g = BornCloseGate()
        val v = bornClose(bornDistanceM = BornCloseGate.BORN_CLOSE_MAX_M)
        g.update(listOf(v), idle, 0L)
        assertTrue(g.isGated(v))
    }

    @Test
    fun `born one metre beyond the close boundary is never gated`() {
        val g = BornCloseGate()
        val v = bornClose(bornDistanceM = BornCloseGate.BORN_CLOSE_MAX_M + 1)
        g.update(listOf(v), idle, 0L)
        assertFalse(g.isGated(v))
    }
}
