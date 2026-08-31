// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.EBikeStage
import es.jjrh.bikeradar.HaStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The status word agrees in gender with the device it describes: el radar
 * takes the masculine, la cámara and la eBike the feminine.
 *
 * This test exists because **no English test can fail on a gender bug.** In
 * `values/strings.xml` the camera keys and the radar keys hold identical
 * words ("Not paired", "Live", "Limited"), so swapping the two mappings in
 * [dashcamLinkLabel] leaves every English assertion and every English golden
 * green while Spanish riders read "La cámara: No emparejado". Spanish is the
 * only place the mapping is observable, so Spanish is where it is pinned.
 *
 * Literals, not the string resources: reading them back from the code under
 * test would keep this green whatever the two became, including both becoming
 * the same word again. Same reasoning as [PairedChipGenderTest], which pins
 * the onboarding chip.
 *
 * Every mapping is asserted, not a sample. A per-device mapping that no test
 * names is one an editor can add a constant to in the wrong gender.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "+es")
class DeviceStatusGenderTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun radar(state: DeviceLinkState) = app.getString(radarLinkLabel(state))
    private fun cam(state: DeviceLinkState) = app.getString(dashcamLinkLabel(state))

    @Test
    fun `the radar reads masculine`() {
        assertEquals("No emparejado", radar(DeviceLinkState.NOT_PAIRED))
        assertEquals("Activo", radar(DeviceLinkState.LIVE))
        assertEquals("Limitado", radar(DeviceLinkState.LIMITED))
    }

    @Test
    fun `the camera reads feminine`() {
        assertEquals("No emparejada", cam(DeviceLinkState.NOT_PAIRED))
        assertEquals("Activa", cam(DeviceLinkState.LIVE))
        // LIMITED is unreachable for a camera today - it is scored off battery
        // adverts alone. Pinned anyway: the mapping is deliberate rather than
        // an else branch, so the day a light-link signal reaches it, it is
        // already in the right gender.
        assertEquals("Limitada", cam(DeviceLinkState.LIMITED))
    }

    @Test
    fun `the genderless states are shared by both`() {
        // Not an accident to be tidied away: these two carry no gender, so one
        // string serves both devices and splitting them would add a second
        // copy to keep in step for no benefit.
        assertEquals("Sin señal", radar(DeviceLinkState.NO_SIGNAL))
        assertEquals("Sin señal", cam(DeviceLinkState.NO_SIGNAL))
        assertEquals("Conectando…", radar(DeviceLinkState.CONNECTING))
        assertEquals("Conectando…", cam(DeviceLinkState.CONNECTING))
    }

    @Test
    fun `the eBike reads feminine`() {
        assertEquals(
            "Activa",
            app.getString(ebikeStatusLabel(receiving = true, stage = EBikeStage.RECEIVING)),
        )
    }

    @Test
    fun `arriving data outranks a stale precondition`() {
        // The two inputs come from different flows - freshness from the data
        // timestamp, stage from the status bus - so they can disagree, and a
        // permission revoked inside the freshness window lands exactly here.
        // Data arriving is the answer that matters; ordering the stage checks
        // first would tell a rider their eBike is unpaired while its data is
        // on screen.
        assertEquals(
            "Activa",
            app.getString(ebikeStatusLabel(receiving = true, stage = EBikeStage.NOT_PERMITTED)),
        )
        assertEquals(
            "Activa",
            app.getString(ebikeStatusLabel(receiving = true, stage = EBikeStage.NO_BONDED_BIKE)),
        )
    }

    @Test
    fun `the eBike names which precondition failed`() {
        // Distinct words, not one shared "waiting": telling a rider to open
        // Flow about a missing Bluetooth grant sends them where nothing can
        // help them.
        assertEquals(
            "Falta permiso de Bluetooth",
            app.getString(ebikeStatusLabel(receiving = false, stage = EBikeStage.NOT_PERMITTED)),
        )
        assertEquals(
            "No emparejada",
            app.getString(ebikeStatusLabel(receiving = false, stage = EBikeStage.NO_BONDED_BIKE)),
        )
        assertEquals(
            "Esperando a Flow",
            app.getString(ebikeStatusLabel(receiving = false, stage = EBikeStage.WAITING)),
        )
    }

    @Test
    fun `Home Assistant reads masculine`() {
        assertEquals("Sin configurar", app.getString(haStatusLabel(HaStatus.NOT_CONFIGURED)))
        assertEquals("Configurado", app.getString(haStatusLabel(HaStatus.CONFIGURED)))
        assertEquals("MQTT listo", app.getString(haStatusLabel(HaStatus.READY)))
        assertEquals("Sin conexión", app.getString(haStatusLabel(HaStatus.UNREACHABLE)))
    }
}
