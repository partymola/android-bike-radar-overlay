// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins the linear pan-formula used for the experimental directional
 * audio feature. The formula tops out at (1.0, 0.7) so the cue is never
 * inaudible on the opposite ear (preserves audibility if the rider's
 * earbud-side battery dies mid-ride), and is symmetric around 0.
 */
@RunWith(RobolectricTestRunner::class)
class AlertBeeperPanTest {

    private fun beeper(): AlertBeeper {
        val ctx = RuntimeEnvironment.getApplication()
        val am = ctx.getSystemService(AudioManager::class.java)
        return AlertBeeper(am)
    }

    @Test
    fun centreIsBalanced() {
        val (l, r) = beeper().computePan(0f)
        assertEquals(0.85f, l, 0.0001f)
        assertEquals(0.85f, r, 0.0001f)
    }

    @Test
    fun fullLeftMaxesLeftMutesNothing() {
        val (l, r) = beeper().computePan(-1f)
        assertEquals(1.0f, l, 0.0001f)
        assertEquals(0.7f, r, 0.0001f)
    }

    @Test
    fun fullRightMaxesRightMutesNothing() {
        val (l, r) = beeper().computePan(+1f)
        assertEquals(0.7f, l, 0.0001f)
        assertEquals(1.0f, r, 0.0001f)
    }

    @Test
    fun halfLeftIsHalfwayBetweenCentreAndFull() {
        val (l, r) = beeper().computePan(-0.5f)
        assertEquals(0.925f, l, 0.0001f)
        assertEquals(0.775f, r, 0.0001f)
    }

    @Test
    fun halfRightIsHalfwayBetweenCentreAndFull() {
        val (l, r) = beeper().computePan(+0.5f)
        assertEquals(0.775f, l, 0.0001f)
        assertEquals(0.925f, r, 0.0001f)
    }

    @Test
    fun outOfRangeClampsToFull() {
        // Defensive: a runaway lateralPos must not produce gains above
        // 1.0 (would clip on the AudioTrack output) or below 0 (silent).
        val left = beeper().computePan(-5f)
        assertEquals(1.0f, left.first, 0.0001f)
        assertEquals(0.7f, left.second, 0.0001f)
        val right = beeper().computePan(+5f)
        assertEquals(0.7f, right.first, 0.0001f)
        assertEquals(1.0f, right.second, 0.0001f)
    }
}
