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
    fun theLastSoundHasTimeToFinishBeforeTheSceneEnds() {
        assertTrue(SoundDemo.cues().last().atMs <= 17_000L)
    }
}
