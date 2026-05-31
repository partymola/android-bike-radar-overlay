// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.data.DashcamOwnership
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [lightsSubtitle] - the consolidated Settings home "Light auto-mode" row
 * subtitle that summarises both lights. The radar-only branches matter most:
 * a rider with no dashcam must NOT be told about a front light they lack.
 */
class LightsSubtitleTest {

    private fun snap(
        rearAuto: Boolean,
        frontAuto: Boolean,
        ownership: DashcamOwnership,
    ) = SnapshotFixtures.defaultPrefsSnapshot().copy(
        radarLightAutoModeEnabled = rearAuto,
        autoLightModeEnabled = frontAuto,
        dashcamOwnership = ownership,
    )

    @Test
    fun `radar-only rider sees only rear state, never the front light`() {
        assertEquals("Rear on", lightsSubtitle(snap(rearAuto = true, frontAuto = false, ownership = DashcamOwnership.NO)))
        assertEquals("Rear off", lightsSubtitle(snap(rearAuto = false, frontAuto = false, ownership = DashcamOwnership.NO)))
        // Front auto-mode flag set but no dashcam owned: still rear-only, no "Front".
        assertEquals(
            "Rear on",
            lightsSubtitle(snap(rearAuto = true, frontAuto = true, ownership = DashcamOwnership.UNANSWERED)),
        )
    }

    @Test
    fun `dashcam owner sees both lights, or a bare Off when both are off`() {
        assertEquals(
            "Rear on · Front on",
            lightsSubtitle(snap(rearAuto = true, frontAuto = true, ownership = DashcamOwnership.YES)),
        )
        assertEquals(
            "Rear on · Front off",
            lightsSubtitle(snap(rearAuto = true, frontAuto = false, ownership = DashcamOwnership.YES)),
        )
        assertEquals(
            "Off",
            lightsSubtitle(snap(rearAuto = false, frontAuto = false, ownership = DashcamOwnership.YES)),
        )
    }
}
