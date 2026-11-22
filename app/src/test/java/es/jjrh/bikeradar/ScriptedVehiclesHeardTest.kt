// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every frame of a scripted scene hears each vehicle in it. */
@RunWith(RobolectricTestRunner::class)
class ScriptedVehiclesHeardTest {

    @Test
    fun theBenchScenarioHearsEachVehicleOnEveryFrame() {
        val svc = SyntheticScenarioService()
        for (t in listOf(12_000L, 12_100L)) {
            val vehicles = svc.scriptAt(t)
            assertTrue("the scene has traffic at $t", vehicles.isNotEmpty())
            assertEquals(setOf(t), vehicles.map { it.heardAtMs }.toSet())
        }
    }

    @Test
    fun theSoundDemoHearsEachVehicleOnEveryFrame() {
        for (t in listOf(5_000L, 5_100L, 16_000L, 18_000L)) {
            val vehicles = SoundDemo.vehiclesAt(t)
            assertTrue("the scene has traffic at $t", vehicles.isNotEmpty())
            assertEquals(setOf(t), vehicles.map { it.heardAtMs }.toSet())
        }
    }
}
