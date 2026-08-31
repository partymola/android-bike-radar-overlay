// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder exists so a link that fails the same way for a week does not
 * report `since=` a few seconds ago.
 *
 * Every property here was previously inline in the radar controller, where a
 * BLE link and a GATT callback stood between a test and the behaviour. It is
 * now shared by the radar and the front camera, so a regression would silently
 * cost both.
 */
class LinkProbeRecorderTest {

    private class Store {
        var value: String? = null
        val writes = mutableListOf<String>()
    }

    private fun recorder(store: Store, clock: () -> Long) = LinkProbeRecorder(
        load = { store.value },
        store = {
            store.value = it
            store.writes += it
        },
        wallClock = clock,
    )

    @Test
    fun `an unchanged answer is not rewritten`() {
        // The reconnect loop retries roughly every 1.5 s. Rewriting an
        // unchanged answer would restamp it, which is the whole defect.
        val s = Store()
        var now = 1_000L
        val r = recorder(s) { now }
        r.record("body-a")
        now = 5_000L
        r.record("body-a")
        r.record("body-a")

        assertEquals("one write for one answer, got ${s.writes}", 1, s.writes.size)
        assertTrue("the stamp must be the FIRST sighting", s.value!!.contains("1000"))
    }

    @Test
    fun `a link alternating between two answers keeps each one's own stamp`() {
        // The case a single last-answer slot gets wrong: each answer differs
        // from the one before it, so every attempt would restamp and both
        // would always read as new.
        val s = Store()
        var now = 1_000L
        val r = recorder(s) { now }
        r.record("body-a")
        now = 2_000L
        r.record("body-b")
        now = 9_000L
        r.record("body-a")

        assertEquals(3, s.writes.size)
        assertTrue("answer A must keep its original stamp, got ${s.writes[2]}", s.writes[2].contains("1000"))
    }

    @Test
    fun `a stored answer survives a process restart`() {
        // Without the seed, the first attempt after a restart rewrites an
        // unchanged answer with a fresh stamp - so a device failing the same
        // way for weeks reports it as new every launch.
        val s = Store()
        s.value = LinkProbe.render(1_000L, "body-a")
        val r = recorder(s) { 99_000L }
        r.record("body-a")

        assertEquals("a restart must not rewrite an unchanged answer", 0, s.writes.size)
        assertTrue(s.value!!.contains("1000"))
    }

    @Test
    fun `a different answer after a restart stamps from now`() {
        // The other half: the seed must not make a genuinely new answer look
        // old by inheriting the stored stamp.
        val s = Store()
        s.value = LinkProbe.render(1_000L, "body-a")
        val r = recorder(s) { 99_000L }
        r.record("body-b")

        assertEquals(1, s.writes.size)
        assertTrue("a new answer stamps now, got ${s.writes[0]}", s.writes[0].contains("99000"))
    }

    @Test
    fun `the stamp map is bounded`() {
        // A pathological link must not grow the map without limit. Eviction is
        // oldest-inserted, so the answer evicted is the one least likely to
        // still be recurring - and it simply restamps if it returns.
        val s = Store()
        var now = 0L
        val r = recorder(s) {
            now += 1_000
            now
        }
        repeat(LinkProbeRecorder.MAX_STAMPS + 4) { r.record("body-$it") }
        // The oldest is gone, so re-recording it stamps fresh rather than
        // returning its original time.
        val before = now
        r.record("body-0")
        assertTrue("evicted answer restamps, got ${s.writes.last()}", s.writes.last().contains("${before + 1000}"))
    }

    @Test
    fun `two links keep separate histories`() {
        // The radar and the camera each own an instance writing its own slot.
        // Sharing one would let whichever reconnected last erase the other's
        // answer, which is exactly the report worth having when only one of
        // the two devices is broken.
        val radar = Store()
        val camera = Store()
        val rr = recorder(radar) { 1_000L }
        val cr = recorder(camera) { 2_000L }
        rr.record("radar-body")
        cr.record("camera-body")

        assertTrue(radar.value!!.contains("radar-body"))
        assertTrue(camera.value!!.contains("camera-body"))
    }
}
