// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.DashcamOwnership
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [lightsSubtitle] - the consolidated Settings home "Light auto-mode" row
 * subtitle that summarises both lights. The radar-only branches matter most:
 * a rider with no dashcam must NOT be told about a front light they lack.
 *
 * Runs under Robolectric so the externalised copy resolves from the real
 * string resources; the assertions pin the resolved English exactly, which
 * also locks the branch-to-copy mapping.
 */
@RunWith(RobolectricTestRunner::class)
class LightsSubtitleTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

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
        assertEquals("Rear on", lightsSubtitle(ctx, snap(rearAuto = true, frontAuto = false, ownership = DashcamOwnership.NO)))
        assertEquals("Rear off", lightsSubtitle(ctx, snap(rearAuto = false, frontAuto = false, ownership = DashcamOwnership.NO)))
        // Front auto-mode flag set but no dashcam owned: still rear-only, no "Front".
        assertEquals(
            "Rear on",
            lightsSubtitle(ctx, snap(rearAuto = true, frontAuto = true, ownership = DashcamOwnership.UNANSWERED)),
        )
    }

    @Test
    fun `dashcam owner sees both lights, or a bare Off when both are off`() {
        assertEquals(
            "Rear on · Front on",
            lightsSubtitle(ctx, snap(rearAuto = true, frontAuto = true, ownership = DashcamOwnership.YES)),
        )
        assertEquals(
            "Rear on · Front off",
            lightsSubtitle(ctx, snap(rearAuto = true, frontAuto = false, ownership = DashcamOwnership.YES)),
        )
        assertEquals(
            "Off",
            lightsSubtitle(ctx, snap(rearAuto = false, frontAuto = false, ownership = DashcamOwnership.YES)),
        )
    }
}
