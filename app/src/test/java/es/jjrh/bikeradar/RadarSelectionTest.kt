// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertFalse
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

    @Test fun `isRadarName matches the family, rejects others`() {
        assertTrue(RadarSelection.isRadarName("RearVue8"))
        assertTrue(RadarSelection.isRadarName("RTL515"))
        assertTrue(RadarSelection.isRadarName("Varia RTL"))
        assertFalse(RadarSelection.isRadarName("Vue"))
        assertFalse(RadarSelection.isRadarName("Bosch eBike"))
        assertFalse(RadarSelection.isRadarName(null))
    }
}
