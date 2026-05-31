// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import es.jjrh.bikeradar.LightAutoModeDecider.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the day/night + dusk/dawn scheduling decision. Pure - the caller supplies
 * `isNight` (from SunsetCalculator) and the sunrise/sunset epochs, so the
 * boundary logic is tested without a clock.
 */
class LightAutoModeDeciderTest {

    @Test fun overrideActiveAppliesNothing() {
        val plan = LightAutoModeDecider.plan(1000, 0, 2000, isNight = false, overrideActive = true)
        assertNull(plan.initial)
        assertNull(plan.flipAtMs)
        assertNull(plan.flipTo)
    }

    @Test fun daytimeWithSunsetAheadFlipsToNightAtSunset() {
        val plan = LightAutoModeDecider.plan(1000, sunriseMs = 0, sunsetMs = 2000, isNight = false, overrideActive = false)
        assertEquals(Phase.DAY, plan.initial)
        assertEquals(2000L, plan.flipAtMs)
        assertEquals(Phase.NIGHT, plan.flipTo)
    }

    @Test fun preDawnNightFlipsToDayAtSunrise() {
        val plan = LightAutoModeDecider.plan(500, sunriseMs = 1000, sunsetMs = 2000, isNight = true, overrideActive = false)
        assertEquals(Phase.NIGHT, plan.initial)
        assertEquals(1000L, plan.flipAtMs)
        assertEquals(Phase.DAY, plan.flipTo)
    }

    @Test fun postSunsetNightSchedulesNoFlip() {
        // After today's sunset (now > sunrise so not pre-dawn): hold night, no flip today.
        val plan = LightAutoModeDecider.plan(3000, sunriseMs = 1000, sunsetMs = 2000, isNight = true, overrideActive = false)
        assertEquals(Phase.NIGHT, plan.initial)
        assertNull(plan.flipAtMs)
        assertNull(plan.flipTo)
    }

    @Test fun exactlySunsetIsNotBeforeSunsetSoNoFlip() {
        // Boundary: now == sunset is NOT < sunset.
        val plan = LightAutoModeDecider.plan(2000, sunriseMs = 0, sunsetMs = 2000, isNight = false, overrideActive = false)
        assertEquals(Phase.DAY, plan.initial)
        assertNull(plan.flipAtMs)
    }

    @Test fun nullSolarBoundsApplyInitialButScheduleNoFlip() {
        val day = LightAutoModeDecider.plan(1000, null, null, isNight = false, overrideActive = false)
        assertEquals(Phase.DAY, day.initial)
        assertNull(day.flipAtMs)
        val night = LightAutoModeDecider.plan(1000, null, null, isNight = true, overrideActive = false)
        assertEquals(Phase.NIGHT, night.initial)
        assertNull(night.flipAtMs)
    }
}
