// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [RadarSelection.shouldLinkRadar] - the hybrid radar-selection fallback
 * matrix. The anti-wrong-radar guard (a chosen+bonded MAC wins, other
 * name-matching radars are ignored) is the safety-relevant case.
 */
class RadarSelectionTest {

    private val macA = "AA:AA:AA:AA:AA:AA"
    private val macB = "BB:BB:BB:BB:BB:BB"

    @Test fun `no choice stored falls back to name-match`() {
        // chosenMac null -> behaviour is exactly the legacy name-match.
        assertTrue(RadarSelection.shouldLinkRadar(macA, nameMatchesRadar = true, chosenMac = null, bondedRadarMacs = setOf(macA)))
        assertFalse(RadarSelection.shouldLinkRadar(macA, nameMatchesRadar = false, chosenMac = null, bondedRadarMacs = setOf(macA)))
    }

    @Test fun `chosen and bonded - only that MAC links`() {
        // The rider pinned A. A links; B (also a radar by name) does NOT -
        // this is the guard against silently streaming the wrong radar.
        val bonded = setOf(macA, macB)
        assertTrue(RadarSelection.shouldLinkRadar(macA, nameMatchesRadar = true, chosenMac = macA, bondedRadarMacs = bonded))
        assertFalse(RadarSelection.shouldLinkRadar(macB, nameMatchesRadar = true, chosenMac = macA, bondedRadarMacs = bonded))
    }

    @Test fun `chosen wins even when the sighting would not name-match`() {
        // An explicit pick overrides the name heuristic entirely.
        assertTrue(RadarSelection.shouldLinkRadar(macA, nameMatchesRadar = false, chosenMac = macA, bondedRadarMacs = setOf(macA)))
    }

    @Test fun `chosen but no longer bonded falls back to name-match`() {
        // A re-pair changed the address: the stored A isn't bonded anymore, so
        // we must not strand the rider - fall back to name-match on whatever
        // radar is actually present (B).
        assertTrue(RadarSelection.shouldLinkRadar(macB, nameMatchesRadar = true, chosenMac = macA, bondedRadarMacs = setOf(macB)))
        assertFalse(RadarSelection.shouldLinkRadar(macB, nameMatchesRadar = false, chosenMac = macA, bondedRadarMacs = setOf(macB)))
    }

    @Test fun `MAC comparison is case-insensitive`() {
        assertTrue(
            RadarSelection.shouldLinkRadar(
                "aa:aa:aa:aa:aa:aa",
                nameMatchesRadar = false,
                chosenMac = macA,
                bondedRadarMacs = setOf("aa:aa:aa:aa:aa:aa"),
            ),
        )
    }

    @Test fun `bonded-membership check is case-insensitive`() {
        // The stored choice and the bonded-set entry can differ in case
        // (Android bond addresses are not casing-stable across re-pairs). The
        // override must still fire - otherwise a case mismatch silently falls
        // back to name-match and re-opens the wrong-radar risk.
        assertTrue(
            RadarSelection.shouldLinkRadar(
                "aa:aa:aa:aa:aa:aa",
                nameMatchesRadar = false,
                chosenMac = "AA:AA:AA:AA:AA:AA",
                bondedRadarMacs = setOf("aa:aa:aa:aa:aa:aa"),
            ),
        )
    }

    @Test fun `ambiguous only when more than one radar bonded`() {
        assertFalse(RadarSelection.isAmbiguous(emptySet()))
        assertFalse(RadarSelection.isAmbiguous(setOf(macA)))
        assertTrue(RadarSelection.isAmbiguous(setOf(macA, macB)))
    }

    private val radarA = RadarSelection.BondedRadar(macA, "RearVue8")
    private val radarB = RadarSelection.BondedRadar(macB, "RTL515")
    private val watch = RadarSelection.BondedRadar("CC:CC:CC:CC:CC:CC", "Watch")

    private val frontCamera = RadarSelection.BondedRadar("AA:BB:CC:DD:EE:0E", "Varia Vue 10101")
    private val unknownRadar = RadarSelection.BondedRadar("AA:BB:CC:DD:EE:0F", "BackLight 9")
    private val eBike = RadarSelection.BondedRadar("AA:BB:CC:DD:EE:09", "smart system eBike")

    @Test fun `the chooser is offered for two named radars, not for one`() {
        assertTrue(RadarSelection.shouldOfferChooser(listOf(radarA, radarB, watch), chosenMac = null))
        assertFalse(RadarSelection.shouldOfferChooser(listOf(radarA, watch), chosenMac = null))
        assertFalse(RadarSelection.shouldOfferChooser(listOf(radarA, watch), chosenMac = macA))
    }

    @Test fun `a pin on the one named radar in another case offers nothing`() {
        // Bond addresses are not casing-stable across re-pairs.
        assertFalse(RadarSelection.shouldOfferChooser(listOf(radarA, watch), chosenMac = macA.lowercase()))
    }

    @Test fun `a pin on another bonded device offers the one named radar back`() {
        // A wrong pin through "my radar isn't listed" streams from the watch
        // only; the chooser is the way back to the real radar.
        assertTrue(RadarSelection.shouldOfferChooser(listOf(radarA, watch), chosenMac = watch.mac.lowercase()))
    }

    @Test fun `a pin no longer bonded offers nothing, since name-match already links`() {
        assertFalse(RadarSelection.shouldOfferChooser(listOf(radarA), chosenMac = "DD:DD:DD:DD:DD:DD"))
    }

    @Test fun `a pin with no named radar bonded offers nothing to choose`() {
        assertFalse(RadarSelection.shouldOfferChooser(listOf(watch), chosenMac = watch.mac))
    }

    @Test fun `a front camera is never offered as the radar`() {
        // Its name carries the vendor word that also names radars. A correct
        // pin on a radar the name-match misses must not put the camera up as
        // the alternative, and the camera alone is nothing to choose.
        assertEquals(listOf(radarA), RadarSelection.choosableRadars(listOf(radarA, frontCamera, watch)))
        assertFalse(RadarSelection.shouldOfferChooser(listOf(unknownRadar, frontCamera), chosenMac = unknownRadar.mac))
        assertFalse(RadarSelection.shouldOfferChooser(listOf(radarA, frontCamera), chosenMac = null))
    }

    private val rearLight = RadarSelection.BondedRadar("AA:BB:CC:DD:EE:07", "Rear light 3")
    private val rearUnit = RadarSelection.BondedRadar("AA:BB:CC:DD:EE:08", "Rear unit 5")

    @Test fun `two radars named only by rear are offered the chooser`() {
        // The live link accepts "rear" alone, so either can be the one streaming.
        assertTrue(RadarSelection.shouldOfferChooser(listOf(rearLight, rearUnit), chosenMac = null))
        assertTrue(RadarSelection.shouldOfferChooser(listOf(radarA, rearLight), chosenMac = null))
        assertEquals(listOf(radarA, rearLight), RadarSelection.choosableRadars(listOf(radarA, rearLight, watch)))
        assertNull(RadarSelection.activeRadar(listOf(radarA, rearLight), null))
    }

    @Test fun `a front camera alone is not named as the radar`() {
        assertNull(RadarSelection.activeRadar(listOf(frontCamera, watch), null))
        assertTrue(RadarSelection.setupOffersPick(listOf(frontCamera, watch), chosenMac = null))
    }

    @Test fun `setup counts a pin or a choosable radar as paired, never a front camera`() {
        assertFalse(RadarSelection.setupHasRadar(listOf(frontCamera, watch), chosenMac = null))
        assertFalse(RadarSelection.setupHasRadar(listOf(watch), chosenMac = "DD:DD:DD:DD:DD:DD"))
        assertTrue(RadarSelection.setupHasRadar(listOf(frontCamera, rearLight), chosenMac = null))
        assertTrue(RadarSelection.setupHasRadar(listOf(watch), chosenMac = watch.mac.lowercase()))
    }

    @Test fun `setup keeps the pick for a radar known only by rear`() {
        assertEquals(rearLight, RadarSelection.activeRadar(listOf(rearLight, watch), null))
        assertTrue(RadarSelection.setupOffersPick(listOf(rearLight, watch), chosenMac = null))
        assertTrue(RadarSelection.setupOffersPick(listOf(rearLight, watch), chosenMac = rearLight.mac))
    }

    @Test fun `the dashcam, the eBike and front cameras are never offered as the radar`() {
        // The camera goes by its name, not yet saved as the dashcam; another
        // device saved as the dashcam goes by its address.
        val savedCam = RadarSelection.BondedRadar("AA:BB:CC:DD:EE:0C", "Front cam")
        val bonded = listOf(radarA, frontCamera, savedCam, eBike, watch)
        val known = listOf(savedCam.mac.lowercase(), eBike.mac, null)
        assertEquals(listOf(radarA, watch), RadarSelection.pickableDevices(bonded, known))
        assertEquals(listOf(watch), RadarSelection.unlistedDevices(bonded, known))
    }

    @Test fun `a front camera beside the radar does not unsettle it`() {
        assertEquals(radarA, RadarSelection.activeRadar(listOf(radarA, frontCamera), null))
        assertFalse(RadarSelection.setupOffersPick(listOf(radarA, frontCamera, watch), chosenMac = null))
    }

    @Test fun `setup offers the pick until a named radar is the active one`() {
        // Nothing settled: offer.
        assertTrue(RadarSelection.setupOffersPick(listOf(watch), chosenMac = null))
        // A radar the app knows by name: never meet the list of watches.
        assertFalse(RadarSelection.setupOffersPick(listOf(radarA, watch), chosenMac = null))
        assertFalse(RadarSelection.setupOffersPick(listOf(radarA, watch), chosenMac = macA.lowercase()))
        // A pick from the list stays changeable where it was made.
        assertTrue(RadarSelection.setupOffersPick(listOf(radarA, watch), chosenMac = watch.mac))
        assertTrue(RadarSelection.setupOffersPick(listOf(unknownRadar), chosenMac = unknownRadar.mac))
        // A front camera is not a settled radar, whatever its name says.
        assertTrue(RadarSelection.setupOffersPick(listOf(frontCamera, unknownRadar), chosenMac = null))
    }

    @Test fun `the active radar is the pin, else the one named radar`() {
        assertEquals(watch, RadarSelection.activeRadar(listOf(radarA, watch), watch.mac))
        assertEquals(watch, RadarSelection.activeRadar(listOf(radarA, watch), watch.mac.lowercase()))
        assertEquals(radarA, RadarSelection.activeRadar(listOf(radarA, watch), null))
        assertEquals(radarA, RadarSelection.activeRadar(listOf(radarA, watch), "DD:DD:DD:DD:DD:DD"))
        assertNull(RadarSelection.activeRadar(listOf(radarA, radarB), null))
        assertNull(RadarSelection.activeRadar(listOf(watch), null))
    }

    @Test fun `isRadarName matches the family, rejects others`() {
        assertTrue(RadarSelection.isRadarName("RearVue8"))
        assertTrue(RadarSelection.isRadarName("RTL515"))
        assertTrue(RadarSelection.isRadarName("Varia RTL"))
        assertFalse(RadarSelection.isRadarName("Vue"))
        assertFalse(RadarSelection.isRadarName("Bosch eBike"))
        assertFalse(RadarSelection.isRadarName(null))
    }
}
