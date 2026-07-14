// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two guards the radar-drop cue's eBike confirmation now rests on: a
 * SUSTAINED above-walking-pace spell (so handling a powered-on bike never reads
 * as a ride) and a short expiry (so a bike moved shortly before the radar goes
 * on has stopped counting by the time it does).
 */
class RidingSpeedGateTest {

    private val pace = RidingSpeedGate.WALKING_PACE_MS
    private val sustain = RidingSpeedGate.SUSTAIN_MS
    private val fresh = RidingSpeedGate.FRESH_MS

    private fun fold(vararg samples: Pair<Long, Float?>): RidingSpeedGate.State {
        var st = RidingSpeedGate.State()
        for ((t, speed) in samples) st = RidingSpeedGate.next(st, t, speed)
        return st
    }

    @Test
    fun wheelingABikeIsNotRiding() {
        // A walked bike runs ~1-1.5 m/s, under the floor: never confirms, however
        // long it goes on for.
        val st = fold(0L to 1.4f, 5_000L to 1.4f, 60_000L to 1.5f)
        assertNull(st.lastRidingMs)
        assertFalse(RidingSpeedGate.ridingFresh(st, 60_000L))
    }

    @Test
    fun aBriefRollOutOfTheRackIsNotRiding() {
        // The rider's own case: the bike gets moved out of a rack - a couple of
        // seconds above walking pace - a minute or so BEFORE the radar is fitted.
        // Neither the roll itself nor the minute that follows may confirm a ride.
        val rollEnd = sustain / 3
        var st = RidingSpeedGate.State()
        var t = 0L
        while (t <= rollEnd) {
            st = RidingSpeedGate.next(st, t, 3.0f) // rolling, above walking pace
            t += 500L
        }
        assertNull(st.lastRidingMs) // spell never reached the sustain floor
        st = RidingSpeedGate.next(st, rollEnd + 1_000L, 0f) // stopped at the door
        assertFalse(RidingSpeedGate.ridingFresh(st, rollEnd + 60_000L))
    }

    @Test
    fun aSustainedSpellAboveWalkingPaceConfirmsRiding() {
        val st = fold(0L to 5.0f, sustain to 5.0f)
        assertEquals(sustain, st.lastRidingMs)
        assertTrue(RidingSpeedGate.ridingFresh(st, sustain))
    }

    @Test
    fun stoppingResetsTheSustainSpellSoItCannotBeAccumulated() {
        // Two half-spells with a stop between them are not one full spell: a rider
        // shuffling a bike around a garage must never add up to a confirmed ride.
        val half = sustain / 2
        val st = fold(
            0L to 5.0f,
            half to 5.0f, // half a spell
            half + 1_000L to 0f, // stopped -> spell resets
            half + 2_000L to 5.0f,
            half + 2_000L + half to 5.0f, // another half spell
        )
        assertNull(st.lastRidingMs)
    }

    @Test
    fun confirmationSurvivesATrafficLightButExpiresAfterThat() {
        // A red light must not un-confirm the ride (a drop while stopped at a
        // light still has to cue), but the confirmation cannot outlive the stop by
        // long, or a parked bike keeps beeping.
        val st = fold(0L to 5.0f, sustain to 5.0f)
        assertTrue(RidingSpeedGate.ridingFresh(st, sustain + fresh - 1))
        assertFalse(RidingSpeedGate.ridingFresh(st, sustain + fresh)) // strict <
    }

    @Test
    fun aFrameWithNoSpeedFieldCarriesNoMovementInformation() {
        // A sparse frame must neither confirm riding nor break a spell in progress.
        var st = fold(0L to 5.0f, 2_000L to null)
        assertEquals(0L, st.movingSinceMs) // spell preserved across the gap
        st = RidingSpeedGate.next(st, sustain, 5.0f)
        assertEquals(sustain, st.lastRidingMs)
    }

    @Test
    fun speedRawConvertsFromHundredthsOfKmh() {
        assertNull(RidingSpeedGate.speedMs(null))
        // 1800 (1/100 km/h) = 18 km/h = 5 m/s.
        assertEquals(5.0f, RidingSpeedGate.speedMs(1800)!!, 0.001f)
        // The walking-pace floor sits at 7.2 km/h: 6 km/h is under it, 10 km/h over.
        assertTrue(RidingSpeedGate.speedMs(600)!! < pace)
        assertTrue(RidingSpeedGate.speedMs(1000)!! > pace)
    }
}
