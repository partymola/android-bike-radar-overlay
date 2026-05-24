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
}
