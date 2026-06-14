// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the four name-heuristic predicates and, critically, their
 * RELATIVE widths: the live-advert gate must stay the narrowest, the
 * accessory matcher the widest, and the radar/dashcam disambiguation
 * must keep front cameras offerable. Device-name literals below are
 * real advertised local names (allowed per the repo naming rules).
 */
class DeviceNameMatcherTest {

    @Test fun rearAdvertMatchesRearUnits() {
        assertTrue(DeviceNameMatcher.isRearAdvert("RearVue8"))
        assertTrue(DeviceNameMatcher.isRearAdvert("RTL515 1234"))
        assertTrue(DeviceNameMatcher.isRearAdvert("rtl511"))
    }

    @Test fun rearAdvertIsConservative() {
        // Vendor word alone must NOT open the live link (could be a front
        // camera) - the pin path covers vendor-named rear units.
        assertFalse(DeviceNameMatcher.isRearAdvert("Varia Vue 49548"))
        assertFalse(DeviceNameMatcher.isRearAdvert("Garmin Edge"))
        assertFalse(DeviceNameMatcher.isRearAdvert("JBL Headphones"))
    }

    @Test fun radarNameIsSupersetOfRearAdvert() {
        for (name in listOf("RearVue8", "RTL515 1234", "Rear Radar")) {
            assertTrue(
                "isRadarName must accept everything isRearAdvert accepts: $name",
                !DeviceNameMatcher.isRearAdvert(name) || DeviceNameMatcher.isRadarName(name),
            )
        }
        // Plus the vendor word for bonded enumeration / the pin picker.
        assertTrue(DeviceNameMatcher.isRadarName("Varia RTL515"))
        assertTrue(DeviceNameMatcher.isRadarName("Varia Radar"))
        assertFalse(DeviceNameMatcher.isRadarName(null))
        assertFalse(DeviceNameMatcher.isRadarName("Pixel Watch"))
    }

    @Test fun unambiguousRadarKeepsFrontCamerasOfferable() {
        assertTrue(DeviceNameMatcher.isUnambiguousRadar("RearVue8"))
        assertTrue(DeviceNameMatcher.isUnambiguousRadar("RTL515 1234"))
        assertTrue(DeviceNameMatcher.isUnambiguousRadar("Varia Radar"))
        // Front cameras share the vendor word but must stay pickable as
        // dashcams.
        assertFalse(DeviceNameMatcher.isUnambiguousRadar("Varia Vue 49548"))
        assertFalse(DeviceNameMatcher.isUnambiguousRadar("Vue-123"))
        assertFalse(DeviceNameMatcher.isUnambiguousRadar("JBL Headphones"))
    }

    @Test fun knownAccessoryIsTheWidest() {
        for (
        name in listOf(
            "RearVue8",
            "RTL515 1234",
            "Varia Vue 49548",
            "Vue-123",
            "Garmin Edge",
        )
        ) {
            assertTrue("accessory matcher must accept $name", DeviceNameMatcher.isKnownAccessory(name))
        }
        assertFalse(DeviceNameMatcher.isKnownAccessory("Pixel Watch"))
        assertFalse(DeviceNameMatcher.isKnownAccessory("JBL Headphones"))
    }

    @Test fun radarNameNullArmReturnsFalseDistinctlyFromEmptyString() {
        // covers DeviceNameMatcher.kt:46
        // The `name?.lowercase() ?: return false` elvis short-circuits on a
        // genuinely null name BEFORE any contains() runs. Pair it against a
        // non-null empty string (which reaches the contains chain and also
        // returns false, but via a different path) so this pins the null arm
        // specifically: a mutant changing the elvis default to `return true`
        // is caught here, where the bundled null assertion alone could be
        // mistaken for the empty-string false path.
        assertFalse("null name must be rejected by the elvis arm", DeviceNameMatcher.isRadarName(null))
        assertFalse("empty name reaches the contains chain and still fails", DeviceNameMatcher.isRadarName(""))
    }

    @Test fun unambiguousRadarMatchesOnRearvueTokenAlone() {
        // covers DeviceNameMatcher.kt:55
        // "RearVue8" satisfies isUnambiguousRadar ONLY through the
        // contains("rearvue") disjunct: it does not contain "rtl", and the
        // `varia && !vue` arm fails (no "varia"). Stripping the "rear" prefix
        // to plain "Vue8" removes the only matching token and must flip the
        // result to false - proving the rearvue disjunct is load-bearing and
        // catching a mutant that deletes or negates it.
        assertTrue(DeviceNameMatcher.isUnambiguousRadar("RearVue8"))
        assertFalse("without 'rear' the rearvue token no longer matches", DeviceNameMatcher.isUnambiguousRadar("Vue8"))
    }

    @Test fun matchingIsCaseInsensitive() {
        assertTrue(DeviceNameMatcher.isRearAdvert("REARVUE8"))
        assertTrue(DeviceNameMatcher.isRadarName("VARIA rtl515"))
        assertTrue(DeviceNameMatcher.isKnownAccessory("GARMIN"))
    }
}
