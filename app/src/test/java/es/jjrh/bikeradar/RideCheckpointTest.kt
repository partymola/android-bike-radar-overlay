// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the crash-checkpoint contract (see RideCheckpoint.kt): the decider
 * writes only on record-relevant change (time-derived snapshot drift and
 * end-time drift must NOT write - the off-episode ticks every 2 s), marks
 * in-flight rides partial and finished rides complete (ended at the radar-off
 * instant), stays settled after a normal post so history is never duplicated,
 * and the store's take() is a destructive read that survives corruption.
 * Robolectric for the real org.json (the plain-JVM stub no-ops).
 */
@RunWith(RobolectricTestRunner::class)
class RideCheckpointTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = RideCheckpointStore({ tmp.root })

    /** A snapshot over the meaningful-ride floor (exposure). */
    private fun meaningfulSnap(overtakes: Int = 5): RideStatsSnapshot = RideStatsAccumulator().snapshot().copy(
        exposureSeconds = RideSummaryNotificationDecider.MIN_EXPOSURE_SECONDS,
        overtakesTotal = overtakes,
    )

    private fun benchSnap(): RideStatsSnapshot = RideStatsAccumulator().snapshot()

    // ── decider ──────────────────────────────────────────────────────────

    @Test
    fun plan_firstMeaningfulSnapshot_checkpointsPartial() {
        val record = RideCheckpointDecider.plan(
            prevRecord = null,
            snap = meaningfulSnap(),
            radarOffSinceMs = null,
            nowMonoMs = 50_000L,
            nowWallMs = 1_000_000L,
        )
        assertNotNull(record)
        assertTrue("an in-flight ride must checkpoint as partial", record!!.partial)
        assertEquals("in-flight end time is 'now'", 1_000_000L, record.endedAtMs)
    }

    @Test
    fun plan_timeDerivedDriftOnly_writesNothing() {
        // Between two ticks with no riding change, the snapshot still
        // differs in its time-derived rate field and the candidate's end
        // time advances. Neither is record-relevant data: the off-episode's
        // 2 s ticks and the in-flight idle ticks must not hit flash.
        val snap = meaningfulSnap()
        val prev = RideCheckpointDecider.plan(null, snap, null, 50_000L, 1_000_000L)!!

        val drifted = snap.copy(alertsPerHourOfRide = 12.3f)
        val record = RideCheckpointDecider.plan(
            prevRecord = prev,
            snap = drifted,
            radarOffSinceMs = null,
            nowMonoMs = 80_000L,
            nowWallMs = 1_030_000L,
        )
        assertNull("rate/end-time drift alone must never write", record)
    }

    @Test
    fun plan_offEpisodeTicks_afterTheEdgeRewrite_writeNothing() {
        val snap = meaningfulSnap()
        val edge = RideCheckpointDecider.plan(null, snap, 90_000L, 100_000L, 2_000_000L)!!
        assertFalse(edge.partial)

        // 2 s later, same stats, same off instant: the conversion re-bases
        // on a fresh now-pair but the record-relevant data is unchanged.
        val next = RideCheckpointDecider.plan(
            prevRecord = edge,
            snap = snap,
            radarOffSinceMs = 90_000L,
            nowMonoMs = 102_000L,
            nowWallMs = 2_002_000L,
        )
        assertNull("the off-episode dwell must be write-free", next)
    }

    @Test
    fun plan_afterNormalPost_staysSettled_neverRecreatesTheCheckpoint() {
        // The service marks the posted record as the settled state instead
        // of nulling it. The following ticks - same stats, radar still off -
        // must not rebuild a checkpoint for a ride history already holds,
        // or every park-then-death would duplicate a history row.
        val snap = meaningfulSnap()
        val posted = RideHistoryRecord.fromSnapshot(
            snap,
            endedAtMs = 2_000_000L - 10_000L,
        )
        val next = RideCheckpointDecider.plan(
            prevRecord = posted,
            snap = snap,
            radarOffSinceMs = 90_000L,
            nowMonoMs = 105_000L,
            nowWallMs = 2_005_000L,
        )
        assertNull("a posted ride must not re-checkpoint", next)
    }

    @Test
    fun plan_benchBlip_neverCheckpoints() {
        val record = RideCheckpointDecider.plan(
            prevRecord = null,
            snap = benchSnap(),
            radarOffSinceMs = null,
            nowMonoMs = 50_000L,
            nowWallMs = 1_000_000L,
        )
        assertNull("a non-meaningful ride must never checkpoint", record)
    }

    @Test
    fun plan_offEdge_rewritesCompleteWithOffInstantEnd() {
        // Same stats, but the radar-off edge flips partial -> complete: the
        // final rewrite must fire even though the ride data is unchanged, and
        // its end time is the off instant converted to wall time.
        val snap = meaningfulSnap()
        val prev = RideCheckpointDecider.plan(null, snap, null, 50_000L, 1_000_000L)!!
        val record = RideCheckpointDecider.plan(
            prevRecord = prev,
            snap = snap,
            radarOffSinceMs = 90_000L,
            nowMonoMs = 100_000L, // off 10 s ago
            nowWallMs = 2_000_000L,
        )
        assertNotNull("the off-edge must rewrite the checkpoint as complete", record)
        assertFalse(record!!.partial)
        assertEquals("ended at the off instant, not 'now'", 2_000_000L - 10_000L, record.endedAtMs)
    }

    @Test
    fun plan_realRidingChange_writes() {
        val prev = RideCheckpointDecider.plan(null, meaningfulSnap(overtakes = 5), null, 1L, 10L)!!
        val record = RideCheckpointDecider.plan(
            prevRecord = prev,
            snap = meaningfulSnap(overtakes = 6),
            radarOffSinceMs = null,
            nowMonoMs = 2L,
            nowWallMs = 20L,
        )
        assertNotNull("a real stats change must write", record)
        assertEquals(6, record!!.overtakes)
    }

    // ── store ────────────────────────────────────────────────────────────

    @Test
    fun writeThenTake_roundTrips_andClearsTheSlot() {
        val s = store()
        val record = RideCheckpointDecider.plan(null, meaningfulSnap(overtakes = 7), null, 50_000L, 1_000_000L)!!
        s.write(record)

        assertEquals(record, s.take())
        assertNull("take() must clear the slot", s.take())
    }

    @Test
    fun take_corruptSlot_returnsNullAndClears() {
        val s = store()
        val dir = java.io.File(tmp.root, RideHistoryStore.HISTORY_DIR)
        dir.mkdirs()
        java.io.File(dir, RideCheckpointStore.FILE_NAME).writeText("{not json")

        assertNull("a corrupt checkpoint must be discarded, not fatal", s.take())
        assertFalse(
            "the corrupt slot must be deleted so it can't wedge future starts",
            java.io.File(dir, RideCheckpointStore.FILE_NAME).exists(),
        )
    }

    @Test
    fun write_overwritesThePreviousSlot() {
        val s = store()
        val first = RideCheckpointDecider.plan(null, meaningfulSnap(1), null, 1L, 10L)!!
        val second = RideCheckpointDecider.plan(null, meaningfulSnap(9), null, 2L, 20L)!!
        s.write(first)
        s.write(second)
        assertEquals("single slot: the newest write wins", second, s.take())
    }

    @Test
    fun take_missingDir_isNull() {
        assertNull(store().take())
    }

    // ── coordinator lifecycle ────────────────────────────────────────────

    private class CoordinatorHarness(root: java.io.File) {
        val store = RideCheckpointStore({ root })
        val history = mutableListOf<RideHistoryRecord>()
        val journal = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val coordinator = RideCheckpointCoordinator(
            store = store,
            appendToHistory = { history.add(it) },
            journal = { journal.add(it) },
            logWarn = { warnings.add(it) },
        )
    }

    @Test
    fun coordinator_recoverOnStart_flushesAndJournals() {
        val h = CoordinatorHarness(tmp.root)
        val leftover = RideCheckpointDecider.plan(null, meaningfulSnap(), null, 1L, 10L)!!
        h.store.write(leftover)

        h.coordinator.recoverOnStart()

        assertEquals(listOf(leftover), h.history)
        assertEquals(1, h.journal.size)
        assertTrue("journal line must carry the partial flag", h.journal[0].contains("partial=true"))
        assertNull("the flushed slot must be cleared", h.store.take())
    }

    @Test
    fun coordinator_recoverOnStart_noCheckpoint_isSilent() {
        val h = CoordinatorHarness(tmp.root)
        h.coordinator.recoverOnStart()
        assertTrue(h.history.isEmpty())
        assertTrue(h.journal.isEmpty())
    }

    @Test
    fun coordinator_tick_writesThenStaysQuiet() {
        val h = CoordinatorHarness(tmp.root)
        val snap = meaningfulSnap()
        h.coordinator.tick({ snap }, null, 1L, 10L)
        h.coordinator.tick({ snap.copy(alertsPerHourOfRide = 9f) }, null, 2L, 20L)

        // One write happened; the drift-only second tick must not replace it.
        val stored = h.store.take()
        assertEquals(10L, stored!!.endedAtMs)
    }

    @Test
    fun coordinator_summaryPosted_thenIdenticalTick_neverRecreatesTheSlot() {
        val h = CoordinatorHarness(tmp.root)
        val snap = meaningfulSnap()
        h.coordinator.tick({ snap }, 90_000L, 100_000L, 2_000_000L) // off-edge write
        val posted = RideHistoryRecord.fromSnapshot(snap, endedAtMs = 1_990_000L)
        h.coordinator.onSummaryPosted(posted)

        h.coordinator.tick({ snap }, 90_000L, 102_000L, 2_002_000L)

        assertNull("a posted ride must not re-create the slot", h.store.take())
    }

    @Test
    fun coordinator_onNewRide_dropsTheSlot() {
        val h = CoordinatorHarness(tmp.root)
        h.coordinator.tick({ meaningfulSnap() }, null, 1L, 10L)
        h.coordinator.onNewRide()
        assertNull(h.store.take())
    }

    @Test
    fun coordinator_tick_swallowsSnapshotRaces() {
        // The snapshot read can race the Main-thread stats writer; the tick
        // must log and survive - a throw would kill the walk-away tick loop
        // that also drives the alarm, summary, and radar-drop cue.
        val h = CoordinatorHarness(tmp.root)
        h.coordinator.tick({ throw ConcurrentModificationException("torn read") }, null, 1L, 10L)
        assertEquals(1, h.warnings.size)
        assertNull(h.store.take())
    }

    // ── record partial flag ──────────────────────────────────────────────

    @Test
    fun partialFlag_roundTripsThroughJson_andDefaultsFalse() {
        val partial = RideCheckpointDecider.plan(null, meaningfulSnap(), null, 1L, 10L)!!
        assertTrue(RideHistoryRecord.fromJsonLine(partial.toJsonLine())!!.partial)

        val complete = partial.copy(partial = false)
        val line = complete.toJsonLine()
        assertFalse(
            "complete records must serialize without the key (old readers unchanged)",
            line.contains("partial"),
        )
        assertFalse(RideHistoryRecord.fromJsonLine(line)!!.partial)
    }
}
