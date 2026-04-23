// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import es.jjrh.bikeradar.DashcamStatusDeriver.Config
import org.junit.Assert.assertEquals
import org.junit.Test

class DashcamStatusDeriverTest {

    private val fresh = 30_000L
    private val cold = 10_000L

    private fun derive(
        config: Config,
        entries: Map<String, BatteryEntry> = emptyMap(),
        nowMs: Long = 0L,
        sessionStartMs: Long = 0L,
        seen: Boolean = false,
    ) = DashcamStatusDeriver.derive(config, entries, nowMs, sessionStartMs, seen, fresh, cold)

    @Test fun warnOffAlwaysOk() {
        val s = derive(Config(warnWhenOff = false, selectedSlug = "vue"), nowMs = 60_000L)
        assertEquals(DashcamStatus.Ok, s)
    }

    @Test fun noSelectionAlwaysOk() {
        val s = derive(Config(warnWhenOff = true, selectedSlug = null), nowMs = 60_000L)
        assertEquals(DashcamStatus.Ok, s)
    }

    @Test fun coldStartGraceShowsSearching() {
        val s = derive(
            Config(warnWhenOff = true, selectedSlug = "vue"),
            nowMs = 5_000L, sessionStartMs = 0L,
        )
        assertEquals(DashcamStatus.Searching, s)
    }

    @Test fun pastGraceNeverSeenShowsMissing() {
        val s = derive(
            Config(warnWhenOff = true, selectedSlug = "vue"),
            nowMs = 15_000L, sessionStartMs = 0L,
        )
        assertEquals(DashcamStatus.Missing, s)
    }

    @Test fun freshEntryShowsOk() {
        val now = 20_000L
        val s = derive(
            Config(warnWhenOff = true, selectedSlug = "vue"),
            entries = mapOf("vue" to BatteryEntry("vue", "Vue", 80, readAtMs = now - 5_000L)),
            nowMs = now, sessionStartMs = 0L, seen = true,
        )
        assertEquals(DashcamStatus.Ok, s)
    }

    @Test fun seenThenStaleShowsDropped() {
        val now = 60_000L
        val s = derive(
            Config(warnWhenOff = true, selectedSlug = "vue"),
            entries = mapOf("vue" to BatteryEntry("vue", "Vue", 80, readAtMs = now - 45_000L)),
            nowMs = now, sessionStartMs = 0L, seen = true,
        )
        assertEquals(DashcamStatus.Dropped, s)
    }

    @Test fun selectedSlugMismatchBehavesAsUnseen() {
        // User selected "vue" but only a "fly6" entry exists — treat as not seen.
        val s = derive(
            Config(warnWhenOff = true, selectedSlug = "vue"),
            entries = mapOf("fly6" to BatteryEntry("fly6", "Fly6", 80, readAtMs = 1_000L)),
            nowMs = 15_000L, sessionStartMs = 0L,
        )
        assertEquals(DashcamStatus.Missing, s)
    }

    @Test fun seenLatchWinsOverColdStart() {
        // If the dashcam was seen and then went stale within the cold-start
        // window, we still treat it as Dropped — Searching is only for the
        // "no signal yet this session" case.
        val now = 5_000L
        val s = derive(
            Config(warnWhenOff = true, selectedSlug = "vue"),
            entries = mapOf("vue" to BatteryEntry("vue", "Vue", 80, readAtMs = now - 40_000L)),
            nowMs = now, sessionStartMs = 0L, seen = true,
        )
        assertEquals(DashcamStatus.Dropped, s)
    }
}
