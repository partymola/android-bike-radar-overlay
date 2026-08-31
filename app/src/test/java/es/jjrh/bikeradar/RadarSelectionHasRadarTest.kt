// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Does this bike have a radar" must agree with what the app actually does.
 *
 * [RadarSelection.shouldLinkRadar] decides whether to stream; every status
 * surface asks [RadarSelection.hasLinkableRadar]. When the two disagree the
 * app reports "Not paired" about a radar delivering targets, drops the home
 * card to an error tone and offers a "Pair" button, all while beeping at
 * traffic. Each case below is a way a narrower test gets that wrong.
 *
 * [RadarSelection.activeRadarName] answers a different question - WHICH unit -
 * and is allowed to be null where `hasLinkableRadar` is true. That gap is the
 * ambiguous case, and it is asserted rather than assumed.
 */
class RadarSelectionHasRadarTest {

    private val radarA = RadarSelection.BondedRadar("AA:AA:AA:AA:AA:AA", "RearVue8")
    private val radarB = RadarSelection.BondedRadar("BB:BB:BB:BB:BB:BB", "RearVue8 spare")
    private val oddName = RadarSelection.BondedRadar("CC:CC:CC:CC:CC:CC", "OffBrandRadar")
    private val watch = RadarSelection.BondedRadar("DD:DD:DD:DD:DD:DD", "Pixel Watch")

    @Test
    fun `nothing bonded means no radar`() {
        assertFalse(RadarSelection.hasLinkableRadar(emptyList(), null))
        assertFalse(RadarSelection.hasLinkableRadar(listOf(watch), null))
    }

    @Test
    fun `one name-matched radar is a radar`() {
        assertTrue(RadarSelection.hasLinkableRadar(listOf(radarA, watch), null))
        assertEquals("RearVue8", RadarSelection.activeRadarName(listOf(radarA, watch), null))
    }

    @Test
    fun `two bonded and none pinned is still a radar`() {
        // The regression this file exists for. shouldLinkRadar falls back to
        // name-match with no pin, so one of them links and streams; a surface
        // requiring a single RESOLVED radar called that rider unpaired.
        val bonded = listOf(radarA, radarB)
        assertTrue(
            RadarSelection.shouldLinkRadar(
                mac = radarA.mac,
                nameMatchesRadar = true,
                chosenMac = null,
                bondedRadarMacs = setOf(radarA.mac, radarB.mac),
            ),
        )
        assertTrue(RadarSelection.hasLinkableRadar(bonded, null))
        // ...and the two questions genuinely differ here, which is why they
        // are two functions: we cannot say WHICH unit is streaming.
        assertEquals(null, RadarSelection.activeRadarName(bonded, null))
    }

    @Test
    fun `an odd-named pinned device is a radar`() {
        // The "my radar isn't listed" escape hatch: the pin wins over the name
        // heuristic in shouldLinkRadar, so a name-match over the bonded list
        // reports no radar while one streams.
        val bonded = listOf(oddName, watch)
        assertTrue(RadarSelection.hasLinkableRadar(bonded, oddName.mac))
        assertEquals("OffBrandRadar", RadarSelection.activeRadarName(bonded, oddName.mac))
    }

    @Test
    fun `a pin is matched case-insensitively`() {
        // Prefs and the Bluetooth stack do not agree on MAC case, and a
        // case-sensitive compare would strand a pinned radar as "Not paired".
        assertTrue(RadarSelection.hasLinkableRadar(listOf(oddName), oddName.mac.lowercase()))
        assertEquals(
            "OffBrandRadar",
            RadarSelection.activeRadarName(listOf(oddName), oddName.mac.lowercase()),
        )
    }

    @Test
    fun `a stale pin falls back rather than stranding the rider`() {
        // The pinned MAC is gone (a re-pair changed the address). Selection
        // falls back to name-match, so the rider still has a radar.
        val bonded = listOf(radarA)
        assertTrue(RadarSelection.hasLinkableRadar(bonded, "99:99:99:99:99:99"))
        assertEquals("RearVue8", RadarSelection.activeRadarName(bonded, "99:99:99:99:99:99"))
    }
}
