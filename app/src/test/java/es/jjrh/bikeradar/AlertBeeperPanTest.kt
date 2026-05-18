// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioManager
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Float multiplication (e.g. 0.7f * 1.0f) is imprecise; data-class
 *  equality on the gain pair fails. Compare component-wise with delta. */
private fun assertStereo(
    expectedLeft: Float,
    expectedRight: Float,
    actual: AlertBeeper.PanResult,
    msg: String = "",
    delta: Float = 0.0001f,
) {
    assertTrue("$msg: expected Stereo result, got $actual", actual is AlertBeeper.PanResult.Stereo)
    val stereo = actual as AlertBeeper.PanResult.Stereo
    assertEquals("$msg: left", expectedLeft, stereo.left, delta)
    assertEquals("$msg: right", expectedRight, stereo.right, delta)
}

private fun assertMono(
    expectedGain: Float,
    actual: AlertBeeper.PanResult,
    msg: String = "",
    delta: Float = 0.0001f,
) {
    assertTrue("$msg: expected Mono result, got $actual", actual is AlertBeeper.PanResult.Mono)
    assertEquals("$msg: gain", expectedGain, (actual as AlertBeeper.PanResult.Mono).gain, delta)
}

/**
 * Exhaustive coverage of the pan-decision matrix:
 *   {pan on / off}
 * x {headphone, built-in speaker, unknown route}
 * x {rotation 0 / 90 / 180 / 270}
 * x {invert off / on}
 *
 * Wrong-ear directional cues are a safety hazard, so these tests pin
 * every load-bearing combination. The pure `resolvePan` function lets
 * us assert exact gains without AudioTrack mocking.
 */
@RunWith(RobolectricTestRunner::class)
class AlertBeeperPanTest {

    private fun beeper(): AlertBeeper {
        val ctx = RuntimeEnvironment.getApplication()
        val am = ctx.getSystemService(AudioManager::class.java)
        return AlertBeeper(am)
    }

    // ── Pan formula (pure shape, route-independent) ──────────────────────

    @Test fun centreIsBalanced() {
        val (l, r) = beeper().computePan(0f)
        assertEquals(0.85f, l, 0.0001f)
        assertEquals(0.85f, r, 0.0001f)
    }

    @Test fun fullLeftMaxesLeftMutesNothing() {
        val (l, r) = beeper().computePan(-1f)
        assertEquals(1.0f, l, 0.0001f)
        assertEquals(0.7f, r, 0.0001f)
    }

    @Test fun fullRightMaxesRightMutesNothing() {
        val (l, r) = beeper().computePan(+1f)
        assertEquals(0.7f, l, 0.0001f)
        assertEquals(1.0f, r, 0.0001f)
    }

    @Test fun halfLeftIsHalfwayBetweenCentreAndFull() {
        val (l, r) = beeper().computePan(-0.5f)
        assertEquals(0.925f, l, 0.0001f)
        assertEquals(0.775f, r, 0.0001f)
    }

    @Test fun halfRightIsHalfwayBetweenCentreAndFull() {
        val (l, r) = beeper().computePan(+0.5f)
        assertEquals(0.775f, l, 0.0001f)
        assertEquals(0.925f, r, 0.0001f)
    }

    @Test fun outOfRangeClampsToFull() {
        val left = beeper().computePan(-5f)
        assertEquals(1.0f, left.first, 0.0001f)
        assertEquals(0.7f, left.second, 0.0001f)
        val right = beeper().computePan(+5f)
        assertEquals(0.7f, right.first, 0.0001f)
        assertEquals(1.0f, right.second, 0.0001f)
    }

    // ── resolvePan: panning disabled ─────────────────────────────────────

    @Test fun panningOffAlwaysReturnsMonoRegardlessOfRouteRotationLateral() {
        // The disabled state is unconditional - no route, rotation, invert,
        // or lateralPos value should produce stereo. Cross-check the matrix.
        val b = beeper()
        for (lat in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            for (rot in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
                for (headphones in listOf(true, false)) {
                    for (speaker in listOf(true, false)) {
                        for (invert in listOf(true, false)) {
                            val r = b.resolvePan(lat, 1f, false, invert, headphones, speaker, rot)
                            assertMono(1f, r, "off must be mono for lat=$lat rot=$rot hp=$headphones spk=$speaker inv=$invert")
                        }
                    }
                }
            }
        }
    }

    // ── resolvePan: headphone route ──────────────────────────────────────

    @Test fun headphoneRouteFullLeftLandsLouderOnLeftChannel() {
        val b = beeper()
        for (rot in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
            assertStereo(1.0f, 0.7f, b.resolvePan(-1f, 1f, true, false, true, false, rot), "headphone+lat=-1+rot=$rot")
        }
    }

    @Test fun headphoneRouteFullRightLandsLouderOnRightChannel() {
        val b = beeper()
        for (rot in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
            assertStereo(0.7f, 1.0f, b.resolvePan(+1f, 1f, true, false, true, false, rot), "headphone+lat=+1+rot=$rot")
        }
    }

    @Test fun headphoneRouteCentreIsBalanced() {
        assertStereo(0.85f, 0.85f, beeper().resolvePan(0f, 1f, true, false, true, false, Surface.ROTATION_90))
    }

    @Test fun headphoneRouteInvertSwapsLR() {
        assertStereo(
            0.7f, 1.0f,
            beeper().resolvePan(-1f, 1f, true, invertLR = true, hasHeadphoneRoute = true, builtinSpeakerActive = false, rotation = Surface.ROTATION_90),
            "headphone+invert: bike-LEFT lands louder on RIGHT channel",
        )
    }

    // ── resolvePan: built-in speaker route ───────────────────────────────

    @Test fun speakerRotation90PansLikeHeadphone() {
        // ROTATION_90 (USB-right landscape): phone's earpiece is on the
        // rider's left. HAL maps audio L to earpiece (always). So audio L
        // already reaches the rider's left ear; no swap needed.
        val b = beeper()
        assertStereo(1.0f, 0.7f, b.resolvePan(-1f, 1f, true, false, false, true, Surface.ROTATION_90))
        assertStereo(0.7f, 1.0f, b.resolvePan(+1f, 1f, true, false, false, true, Surface.ROTATION_90))
    }

    @Test fun speakerRotation270SwapsToCompensateForHALMapping() {
        // ROTATION_270 (USB-left landscape): phone flipped 180 in landscape.
        // Earpiece (where HAL routes audio L) is now on the rider's RIGHT.
        // To reach the rider's left ear, LOUDER gain must go on audio R -
        // which Android routes to the bottom-main speaker, now on the
        // rider's left.
        val b = beeper()
        assertStereo(0.7f, 1.0f, b.resolvePan(-1f, 1f, true, false, false, true, Surface.ROTATION_270),
            "speaker+rot=270: bike-LEFT must put LOUDER gain on R channel")
        assertStereo(1.0f, 0.7f, b.resolvePan(+1f, 1f, true, false, false, true, Surface.ROTATION_270),
            "speaker+rot=270: bike-RIGHT must put LOUDER gain on L channel")
    }

    @Test fun speakerPortraitFallsBackToMono() {
        // Portrait: speakers are physically close (bottom edge); no usable
        // lateralisation. Mono.
        val b = beeper()
        for (rot in listOf(Surface.ROTATION_0, Surface.ROTATION_180)) {
            for (lat in listOf(-1f, 0f, 1f)) {
                assertMono(1f, b.resolvePan(lat, 1f, true, false, false, true, rot),
                    "speaker+portrait rot=$rot lat=$lat must be mono")
            }
        }
    }

    @Test fun speakerInvertCancelsRotation270Swap() {
        // XOR: rotation-270 swaps AND invertLR swaps; both fire = no net
        // swap. Matches no-swap-no-invert (rotation 90 headphone-equivalent).
        assertStereo(
            1.0f, 0.7f,
            beeper().resolvePan(-1f, 1f, true, invertLR = true, hasHeadphoneRoute = false, builtinSpeakerActive = true, rotation = Surface.ROTATION_270),
        )
    }

    @Test fun speakerInvertCompoundsRotation90Swap() {
        // ROTATION_90 doesn't swap; invertLR alone produces the swapped result.
        assertStereo(
            0.7f, 1.0f,
            beeper().resolvePan(-1f, 1f, true, invertLR = true, hasHeadphoneRoute = false, builtinSpeakerActive = true, rotation = Surface.ROTATION_90),
        )
    }

    // ── resolvePan: unknown / unsupported route ──────────────────────────

    @Test fun unknownRouteFallsBackToMono() {
        // Neither headphone nor built-in speaker. Examples: BT car bus
        // (TYPE_BUS), portable BLE speaker, casting target. Mono is the
        // safe fallback (spatial layout unknown).
        val b = beeper()
        for (rot in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
            assertMono(1f, b.resolvePan(-1f, 1f, true, false, false, false, rot),
                "unknown route rot=$rot must be mono")
        }
    }

    // ── Volume scaling ───────────────────────────────────────────────────

    @Test fun monoGainScalesStereoGains() {
        // resolvePan multiplies the pan-formula output by monoGain so
        // user volume cuts both channels proportionally. Half volume
        // (0.5) on full-left pan -> (0.5, 0.35).
        assertStereo(
            0.5f, 0.35f,
            beeper().resolvePan(-1f, 0.5f, true, false, true, false, Surface.ROTATION_90),
        )
    }

    @Test fun monoGainScalesMonoResult() {
        assertMono(0.25f, beeper().resolvePan(-1f, 0.25f, false, false, true, false, Surface.ROTATION_90))
    }
}
