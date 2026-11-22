// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo teaches the sounds in the order the rider was promised, as the
 * ride's decider makes them. A decider change that moves or drops one of these
 * reds here: re-check the scene and its captions, then update the times.
 */
class SoundDemoTest {

    private val sliderRange = Prefs.MIN_ALERT_MAX_DISTANCE_M..Prefs.MAX_ALERT_MAX_DISTANCE_M

    @Test
    fun theSceneSoundsOneTwoThreeBeepsThenTheAllClearThenTheUrgentWarning() {
        assertEquals(
            listOf(
                SoundDemo.Moment(6_000L, AlertCue.Beep(1)),
                SoundDemo.Moment(7_600L, AlertCue.Beep(2)),
                SoundDemo.Moment(9_200L, AlertCue.Beep(3)),
                SoundDemo.Moment(12_900L, AlertCue.Clear),
                SoundDemo.Moment(15_900L, AlertCue.Urgent),
            ),
            SoundDemo.cues(alertMaxM = 20),
        )
    }

    @Test
    fun theSceneSoundsTheSameFiveCuesAtTheShortestAlertDistance() {
        assertEquals(
            listOf(
                SoundDemo.Moment(8_400L, AlertCue.Beep(1)),
                SoundDemo.Moment(9_200L, AlertCue.Beep(2)),
                SoundDemo.Moment(10_000L, AlertCue.Beep(3)),
                SoundDemo.Moment(12_900L, AlertCue.Clear),
                SoundDemo.Moment(16_600L, AlertCue.Urgent),
            ),
            SoundDemo.cues(alertMaxM = 10),
        )
    }

    @Test
    fun theSceneSoundsTheSameFiveCuesAtTheLongestAlertDistance() {
        assertEquals(
            listOf(
                SoundDemo.Moment(1_200L, AlertCue.Beep(1)),
                SoundDemo.Moment(4_400L, AlertCue.Beep(2)),
                SoundDemo.Moment(7_600L, AlertCue.Beep(3)),
                SoundDemo.Moment(12_900L, AlertCue.Clear),
                SoundDemo.Moment(14_400L, AlertCue.Urgent),
            ),
            SoundDemo.cues(alertMaxM = 40),
        )
    }

    @Test
    fun theUrgentWarningComesWhileTheRiderIsStopped() {
        for (a in sliderRange) {
            val urgent = SoundDemo.cues(a).single { it.cue == AlertCue.Urgent }
            assertEquals("at $a m", 0f, SoundDemo.bikeSpeedAt(urgent.atMs))
        }
    }

    @Test
    fun theSceneIsTheSameOnEveryRun() {
        assertEquals(SoundDemo.cues(alertMaxM = 20), SoundDemo.cues(alertMaxM = 20))
    }

    @Test
    fun playbackSoundsEachCueOnceWhenTheClockPassesIt() {
        val played = mutableListOf<AlertCue>()
        val playback = SoundDemo.Playback(alertMaxM = 20) { played += it }
        playback.advanceTo(5_999L)
        assertEquals(emptyList<AlertCue>(), played)
        playback.advanceTo(6_000L)
        playback.advanceTo(6_000L)
        assertEquals(listOf<AlertCue>(AlertCue.Beep(1)), played)
        assertEquals(AlertCue.Beep(1), playback.lastCue)
    }

    @Test
    fun aFrameThatJumpsAheadStillPlaysEveryCueInOrder() {
        val played = mutableListOf<AlertCue>()
        SoundDemo.Playback(alertMaxM = 20) { played += it }.advanceTo(SoundDemo.DURATION_MS)
        assertEquals(listOf(AlertCue.Beep(1), AlertCue.Beep(2), AlertCue.Beep(3), AlertCue.Clear, AlertCue.Urgent), played)
    }

    @Test
    fun playbackSoundsTheScenePlannedForItsAlertDistance() {
        val played = mutableListOf<AlertCue>()
        val playback = SoundDemo.Playback(alertMaxM = 10) { played += it }
        playback.advanceTo(8_399L)
        assertEquals(emptyList<AlertCue>(), played)
        playback.advanceTo(8_400L)
        assertEquals(listOf<AlertCue>(AlertCue.Beep(1)), played)
    }

    @Test
    fun theSceneLastsTheTwentySecondsItsIntroductionPromises() {
        assertEquals(20_000L, SoundDemo.DURATION_MS)
    }

    @Test
    fun everyAlertDistanceSoundsTheFiveCuesInOrderWithTimeToFinish() {
        for (a in sliderRange) {
            val cues = SoundDemo.cues(a)
            assertEquals(
                "at $a m",
                listOf(AlertCue.Beep(1), AlertCue.Beep(2), AlertCue.Beep(3), AlertCue.Clear, AlertCue.Urgent),
                cues.map { it.cue },
            )
            // The closest pair across the slider's distances is 800 ms apart, at 10 m.
            val beepGaps = cues.filter { it.cue is AlertCue.Beep }.zipWithNext { x, y -> y.atMs - x.atMs }
            assertTrue("at $a m beeps $beepGaps ms apart", beepGaps.all { it >= 800L })
            assertTrue("at $a m the last cue is at ${cues.last().atMs}", SoundDemo.DURATION_MS - cues.last().atMs >= 1_500L)
        }
    }

    @Test
    fun carOneIsOutOfRangeAtEveryAlertDistanceBeforeItSoundsAndHasGoneBeforeTheAllClear() {
        for (a in sliderRange) {
            val cues = SoundDemo.cues(a)
            assertTrue("at $a m", SoundDemo.vehiclesAt(1_000L).single().distanceM > a)
            val allClear = cues.single { it.cue == AlertCue.Clear }.atMs
            assertEquals("at $a m", emptyList<Vehicle>(), SoundDemo.vehiclesAt(allClear - 100L))
            val carTwoDrawn = (0L..SoundDemo.DURATION_MS step SoundDemo.FRAME_MS)
                .first { t -> SoundDemo.vehiclesAt(t).any { it.id == 2 && it.distanceM <= a } }
            assertTrue("at $a m the all-clear is at $allClear, car 2 drawn at $carTwoDrawn", allClear < carTwoDrawn)
        }
    }

    @Test
    fun eachCarIsDrawnOneFrameBeforeItsFirstSoundAtEveryAlertDistance() {
        // Before that the strip is empty, which is what the silent caption says.
        for (a in sliderRange) {
            fun firstDrawn(car: Int) = (0L..SoundDemo.DURATION_MS step SoundDemo.FRAME_MS)
                .first { t -> SoundDemo.vehiclesAt(t).any { it.id == car && it.distanceM <= a } }
            val cues = SoundDemo.cues(a)
            assertEquals("car 1 at $a m", cues.first().atMs - SoundDemo.FRAME_MS, firstDrawn(car = 1))
            assertEquals("car 2 at $a m", cues.last().atMs - SoundDemo.FRAME_MS, firstDrawn(car = 2))
        }
    }

    @Test
    fun carOneIsBornBeyondTheLongestAlertDistanceAtAFixedSpeed() {
        assertEquals(emptyList<Vehicle>(), SoundDemo.vehiclesAt(900L))
        val born = SoundDemo.vehiclesAt(1_000L).single()
        assertEquals(41, born.distanceM)
        assertEquals(-4.15f, born.speedMs)
        assertEquals(36, SoundDemo.vehiclesAt(2_000L).single().distanceM)
        assertEquals(20, SoundDemo.vehiclesAt(6_000L).single().distanceM)
    }

    @Test
    fun carOneReachesTheRiderBeforeTheBrakingStartsAndIsGoneAfter() {
        val last = SoundDemo.vehiclesAt(10_800L).single()
        assertEquals(0, last.distanceM)
        assertEquals(6f, SoundDemo.bikeSpeedAt(10_800L))
        assertEquals(emptyList<Vehicle>(), SoundDemo.vehiclesAt(10_900L))
    }

    @Test
    fun carTwoIsBornBeyondTheLongestAlertDistanceAndClosesAtFourteen() {
        assertEquals(emptyList<Vehicle>(), SoundDemo.vehiclesAt(13_900L))
        val born = SoundDemo.vehiclesAt(14_000L).single()
        assertEquals(45, born.distanceM)
        assertEquals(-14f, born.speedMs)
        assertEquals(31, SoundDemo.vehiclesAt(15_000L).single().distanceM)
    }

    @Test
    fun carTwoHoldsAtFourMetresOnceItGetsThere() {
        val closing = SoundDemo.vehiclesAt(16_900L).single()
        assertEquals(4, closing.distanceM)
        assertEquals(-14f, closing.speedMs)
        val held = SoundDemo.vehiclesAt(17_000L).single()
        assertEquals(4, held.distanceM)
        assertEquals(0f, held.speedMs)
        // Every frame of the scene hears it, so only that changes.
        assertEquals(held.copy(heardAtMs = 19_900L), SoundDemo.vehiclesAt(19_900L).single())
    }

    @Test
    fun aLongerAlertDistanceSoundsTheFirstBeepSooner() {
        val atTen = SoundDemo.cues(10).first().atMs
        val atForty = SoundDemo.cues(40).first().atMs
        assertTrue("first beep at $atTen ms at 10 m, $atForty ms at 40 m", atTen - atForty >= 7_000L)
    }
}
