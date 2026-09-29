// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo teaches the sounds in the order the rider was promised, as the
 * ride's decider makes them. A decider change that moves or drops one of these
 * reds here: re-check the scene and its captions, then update the times.
 */
class SoundDemoTest {

    @Test
    fun theSceneSoundsOneTwoThreeBeepsThenTheAllClearThenTheUrgentWarning() {
        assertEquals(
            listOf(
                SoundDemo.Moment(3_400L, AlertCue.Beep(1)),
                SoundDemo.Moment(5_100L, AlertCue.Beep(2)),
                SoundDemo.Moment(6_800L, AlertCue.Beep(3)),
                SoundDemo.Moment(10_500L, AlertCue.Clear),
                SoundDemo.Moment(15_300L, AlertCue.Urgent),
            ),
            SoundDemo.cues(),
        )
    }

    @Test
    fun theUrgentWarningComesWhileTheRiderIsStopped() {
        val urgent = SoundDemo.cues().single { it.cue == AlertCue.Urgent }
        assertEquals(0f, SoundDemo.bikeSpeedAt(urgent.atMs))
    }

    @Test
    fun theSceneIsTheSameOnEveryRun() {
        assertEquals(SoundDemo.cues(), SoundDemo.cues())
    }

    @Test
    fun playbackSoundsEachCueOnceWhenTheClockPassesIt() {
        val played = mutableListOf<AlertCue>()
        val playback = SoundDemo.Playback { played += it }
        playback.advanceTo(3_399L)
        assertEquals(emptyList<AlertCue>(), played)
        playback.advanceTo(3_400L)
        playback.advanceTo(3_400L)
        assertEquals(listOf<AlertCue>(AlertCue.Beep(1)), played)
        assertEquals(AlertCue.Beep(1), playback.lastCue)
    }

    @Test
    fun aFrameThatJumpsAheadStillPlaysEveryCueInOrder() {
        val played = mutableListOf<AlertCue>()
        SoundDemo.Playback { played += it }.advanceTo(SoundDemo.DURATION_MS)
        assertEquals(listOf(AlertCue.Beep(1), AlertCue.Beep(2), AlertCue.Beep(3), AlertCue.Clear, AlertCue.Urgent), played)
    }

    @Test
    fun theSceneLastsTheTwentySecondsItsIntroductionPromises() {
        assertEquals(20_000L, SoundDemo.DURATION_MS)
    }

    @Test
    fun theLastSoundHasTimeToFinishBeforeTheSceneEnds() {
        assertTrue(SoundDemo.DURATION_MS - SoundDemo.cues().last().atMs >= 1_500L)
    }

    @Test
    fun eachCarIsDrawnOneFrameBeforeItsFirstSound() {
        // Before that the strip is empty, which is what the silent caption says.
        fun firstDrawnAfter(fromMs: Long) = (fromMs..SoundDemo.DURATION_MS step SoundDemo.FRAME_MS)
            .first { t -> SoundDemo.vehiclesAt(t).any { it.distanceM <= SoundDemo.VISUAL_MAX_M } }
        assertEquals(20, SoundDemo.VISUAL_MAX_M)
        assertEquals(3_300L, firstDrawnAfter(0L))
        assertEquals(15_200L, firstDrawnAfter(10_000L))
    }
}
