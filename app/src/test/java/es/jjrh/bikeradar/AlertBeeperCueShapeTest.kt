// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs

/**
 * Pins that the imminent-impact cue is recognisably NOT a normal close-pass
 * beep. The whole safety value of the urgent cue is that the rider hears a
 * distinct pattern, encoded by pulse COUNT and cadence (not fine pitch). If
 * a future tweak collapsed the urgent cue into a 3-beep, nothing else would
 * catch it - the PCM is otherwise opaque (Robolectric exposes no AudioTrack
 * buffer), so these tests assert on the generated mono PCM directly.
 */
@RunWith(RobolectricTestRunner::class)
class AlertBeeperCueShapeTest {

    private fun beeper(): AlertBeeper {
        val am = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        return AlertBeeper(am)
    }

    /**
     * Counts tone bursts in a cue. A real inter-pulse gap is thousands of
     * zero samples (>= 2205 at 44.1 kHz); a within-tone sine zero-crossing is
     * one or two samples. A 500-sample threshold sits safely between, so each
     * non-zero sample that follows a long silent run starts a new pulse.
     */
    private fun countPulses(pcm: ShortArray, gapThreshold: Int = 500): Int {
        var pulses = 0
        var zeroRun = gapThreshold // treat the start as preceded by a gap
        for (s in pcm) {
            if (s.toInt() == 0) {
                zeroRun++
            } else {
                if (zeroRun >= gapThreshold) pulses++
                zeroRun = 0
            }
        }
        return pulses
    }

    @Test fun urgentHasFourPulses_closePassHasThree() {
        val b = beeper()
        assertEquals(3, countPulses(b.buildBeepPcm(3)))
        assertEquals(4, countPulses(b.buildUrgentPcm()))
    }

    @Test fun beepPulseCountMatchesRequestedCount() {
        val b = beeper()
        assertEquals(1, countPulses(b.buildBeepPcm(1)))
        assertEquals(2, countPulses(b.buildBeepPcm(2)))
    }

    @Test fun urgentIsStructurallyDistinctFromThreeBeep() {
        val b = beeper()
        val urgent = b.buildUrgentPcm()
        val threeBeep = b.buildBeepPcm(3)
        // Different pulse count AND different total length: recognisably not
        // a normal beep, by count and by cadence.
        assertNotEquals(countPulses(threeBeep), countPulses(urgent))
        assertTrue("urgent and 3-beep must not be the same length", urgent.size != threeBeep.size)
    }

    @Test fun statusCuesAreSeparableByPulseCount() {
        // The three rear-radar status cues are discriminated by COUNT, not fine
        // pitch (the noisy-London rule). Reconnect=1, battery=2, drop=3 - a
        // distinct count each, so collapsing any two together fails here.
        val b = beeper()
        assertEquals(1, countPulses(b.buildRadarReconnectedPcm()))
        assertEquals(2, countPulses(b.buildCriticalBatteryPcm()))
        assertEquals(3, countPulses(b.buildRadarDroppedPcm()))
    }

    /**
     * Estimate a cue's carrier frequency by counting sign changes across the
     * non-silent samples. Two sign changes per sine cycle, divided by the total
     * tone time (silent inter-pulse gaps contribute no changes), gives Hz. Good
     * to a few percent - enough to pin the timbre-CLASS band, which is all the
     * cue design relies on.
     */
    private fun estimateCarrierHz(pcm: ShortArray, totalToneMs: Int): Double {
        var changes = 0
        var prevSign = 0
        for (s in pcm) {
            val sign = when {
                s > 0 -> 1
                s < 0 -> -1
                else -> 0
            }
            if (sign != 0) {
                if (prevSign != 0 && sign != prevSign) changes++
                prevSign = sign
            }
        }
        return changes / 2.0 / (totalToneMs / 1000.0)
    }

    /** Per-pulse peak amplitude and the inter-pulse gap lengths (in samples),
     *  in order. Splits on silent runs >= [gapThreshold], mirroring
     *  [countPulses]. */
    private fun analysePulses(pcm: ShortArray, gapThreshold: Int = 500): Pair<List<Int>, List<Int>> {
        val peaks = mutableListOf<Int>()
        val gaps = mutableListOf<Int>()
        var zeroRun = gapThreshold
        var inPulse = false
        var curPeak = 0
        for (s in pcm) {
            val v = s.toInt()
            if (v == 0) {
                zeroRun++
                if (inPulse && zeroRun >= gapThreshold) {
                    peaks.add(curPeak)
                    curPeak = 0
                    inPulse = false
                }
            } else {
                if (!inPulse) {
                    if (peaks.isNotEmpty()) gaps.add(zeroRun) // real gap, not the leading run
                    inPulse = true
                }
                zeroRun = 0
                val a = abs(v)
                if (a > curPeak) curPeak = a
            }
        }
        if (inPulse) peaks.add(curPeak)
        return peaks to gaps
    }

    @Test fun statusCuesShareOneCarrier_wellBelowTheThreatBeeps() {
        // The status class (drop / battery / reconnect) sits on one ~900 Hz
        // carrier - raised out of the traffic-masked low band but a full class
        // below the sharp threat beeps (3200 Hz) and urgent (3800 Hz). Cues are
        // told apart by COUNT, never a step off this carrier; the threat band
        // stays untouched.
        val b = beeper()
        val drop = estimateCarrierHz(b.buildRadarDroppedPcm(), totalToneMs = 3 * 130)
        val battery = estimateCarrierHz(b.buildCriticalBatteryPcm(), totalToneMs = 2 * 160)
        val reconnect = estimateCarrierHz(b.buildRadarReconnectedPcm(), totalToneMs = 150)
        for (f in listOf(drop, battery, reconnect)) {
            assertTrue("status carrier $f Hz should be ~900 Hz", abs(f - 900.0) < 80.0)
        }
        // Threat carriers unchanged and a clear class apart.
        val threat = estimateCarrierHz(b.buildBeepPcm(1), totalToneMs = 80)
        assertTrue("close-pass beep should stay ~3200 Hz, was $threat", abs(threat - 3200.0) < 150.0)
        assertTrue("status carrier must be far below the threat carrier", drop < threat / 2)
    }

    @Test fun beepTiersHaveDistinctRhythms_sameCarrier() {
        // Each awareness tier (= a DISTANCE band) gets its own rhythm so the
        // tiers are easier to tell apart under load: tier 3 is a tighter
        // triplet than tier 2's slow pair. This is a redundant read on distance
        // (count is the primary signal), NOT a speed/urgency code - the carrier
        // and the count<->distance mapping are unchanged. The count assertions
        // in the tests above guard that tier identity is untouched.
        val b = beeper()
        val (_, gaps2) = analysePulses(b.buildBeepPcm(2))
        val (_, gaps3) = analysePulses(b.buildBeepPcm(3))
        assertEquals("tier 2 has one inter-pulse gap", 1, gaps2.size)
        assertEquals("tier 3 has two inter-pulse gaps", 2, gaps3.size)
        assertTrue("the tiers must have distinct rhythms", gaps3.max() < gaps2.min())
        // Same carrier across tiers: the tier is not a pitch step.
        val f2 = estimateCarrierHz(b.buildBeepPcm(2), totalToneMs = 2 * 80)
        val f3 = estimateCarrierHz(b.buildBeepPcm(3), totalToneMs = 3 * 80)
        assertTrue("tiers must share one carrier", abs(f2 - f3) < 120.0)
    }

    @Test fun urgentCueLooms_louderAndFasterAcrossTheBurst() {
        // The looming envelope: each urgent pulse is louder than the last AND
        // the gaps accelerate (shrink), so the burst perceptually rushes at the
        // rider - loudness + tempo, never a pitch motif.
        val b = beeper()
        val (peaks, gaps) = analysePulses(b.buildUrgentPcm())
        assertEquals("urgent still has four pulses", 4, peaks.size)
        assertEquals("urgent has three inter-pulse gaps", 3, gaps.size)
        peaks.zipWithNext().forEach { (a, c) ->
            assertTrue("each urgent pulse must be louder than the last ($a -> $c)", c > a)
        }
        gaps.zipWithNext().forEach { (a, c) ->
            assertTrue("urgent gaps must accelerate (shrink) ($a -> $c)", c < a)
        }
    }
}
