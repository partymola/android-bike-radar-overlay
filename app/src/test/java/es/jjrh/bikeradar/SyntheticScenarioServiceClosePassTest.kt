// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The Debug scenario has to still demonstrate a close pass.
 *
 * `SyntheticScenarioServiceCueTest` replays the same script through
 * [AlertDecider], which reads [Vehicle.rangeXm] and fails OPEN on 0f, so it
 * cannot see a scripted vehicle that [ClosePassDetector] refuses. That is the
 * gap this covers: the detector reads [Vehicle.rangeXmRaw], whose default of 0f
 * carries no usable clearance, so a scenario built with the constructor rather
 * than the file's own builder produces no close pass at all and nothing else in
 * the suite notices.
 */
@RunWith(RobolectricTestRunner::class)
class SyntheticScenarioServiceClosePassTest {

    private fun service(): SyntheticScenarioService = Robolectric.buildService(SyntheticScenarioService::class.java).get()

    @Test
    fun theScenarioStillProducesAClosePass() {
        val svc = service()
        val detector = ClosePassDetector()
        val cfg = ClosePassDetector.Config(enabled = true)
        var emits = 0
        // The scenario's own cadence, so the frame spacing matches the demo.
        for (step in 0..600) {
            val tMs = step * 100L
            emits += detector.decide(svc.scriptAt(tMs), svc.bikeSpeedAt(tMs), tMs, cfg).size
        }
        assertTrue(
            "the scripted traffic must still demonstrate a close pass; it produced $emits",
            emits > 0,
        )
    }

    @Test
    fun everyScriptedVehicleCarriesAMeasuredLateralReading() {
        // The property behind it, stated directly so a failure names the cause
        // rather than the symptom. A scripted vehicle whose raw lateral is zero
        // is one the detector can never measure.
        val svc = service()
        val offenders = (0..600)
            .flatMap { svc.scriptAt(it * 100L) }
            .filter { it.lateralPos != 0f && it.rangeXmRaw == 0f }
            .map { "id=${it.id} lateralPos=${it.lateralPos}" }
            .distinct()
        assertTrue(
            "a scripted vehicle declares a lateral position but no raw reading: $offenders",
            offenders.isEmpty(),
        )
    }
}
