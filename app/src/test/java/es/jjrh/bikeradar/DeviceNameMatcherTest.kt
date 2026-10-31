// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the name-heuristic predicates and, critically, their RELATIVE
 * widths: a radar name is exactly what [DeviceNameMatcher.isRearAdvert]
 * accepts, and the radar/dashcam disambiguation must keep front cameras
 * offerable. "RearVue8" is a measured local name (allowed per the repo naming
 * rules); the other literals are made up or unmeasured, to probe a boundary.
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
        assertFalse(DeviceNameMatcher.isRearAdvert("Varia Vue 84509"))
        assertFalse(DeviceNameMatcher.isRearAdvert("Garmin Edge"))
        assertFalse(DeviceNameMatcher.isRearAdvert("JBL Headphones"))
    }

    @Test fun theVendorWordAloneIsNotARadarName() {
        for (name in listOf("Varia RVR315", "Varia Vue 84509", "Varia RTL")) {
            assertFalse(name, DeviceNameMatcher.isRadarName(name))
        }
        assertFalse(DeviceNameMatcher.isRadarName(null))
        assertFalse(DeviceNameMatcher.isRadarName("Pixel Watch"))
    }

    @Test fun aRadarNameEqualsWhatIsRearAdvertAccepts() {
        val expected = listOf(
            "RearVue8" to true,
            "REARVUE8" to true,
            "RTL12345" to true,
            "rtl511" to true,
            "RTL515 1234" to true,
            "RTL_515" to true,
            "eRTL615" to true,
            "eRTL-12345" to true,
            "Varia RTL515" to true,
            "Varia RTL 515" to true,
            "VARIA rtl515" to true,
            "Rear Radar" to true,
            "Rear light 3" to true,
            "Varia Rear Vue" to true,
            "Rear Vue 2" to true,
            "Varia RVR315" to false,
            "Varia Radar" to false,
            "VARIA RADAR" to false,
            "Varia RTL" to false,
            "Varia Vue 84509" to false,
            "Vue-123" to false,
            "VUE-12345" to false,
            "Vue8" to false,
            "RTL" to false,
            "RTL--5" to false,
            "Garmin Edge" to false,
            "Turtle Beach Stealth" to false,
            "Pixel Watch" to false,
            "" to false,
        )
        for ((name, isRadar) in expected) {
            assertEquals("isRadarName($name)", isRadar, DeviceNameMatcher.isRadarName(name))
            assertEquals("isRearAdvert($name)", isRadar, DeviceNameMatcher.isRearAdvert(name))
        }
    }

    @Test fun unambiguousRadarKeepsFrontCamerasOfferable() {
        assertTrue(DeviceNameMatcher.isUnambiguousRadar("RearVue8"))
        assertTrue(DeviceNameMatcher.isUnambiguousRadar("RTL515 1234"))
        assertTrue(DeviceNameMatcher.isUnambiguousRadar("Varia Radar"))
        // Front cameras share the vendor word but must stay pickable as
        // dashcams.
        assertFalse(DeviceNameMatcher.isUnambiguousRadar("Varia Vue 84509"))
        assertFalse(DeviceNameMatcher.isUnambiguousRadar("Vue-123"))
        assertFalse(DeviceNameMatcher.isUnambiguousRadar("JBL Headphones"))
    }

    @Test fun aNameTheLiveLinkAcceptsIsNeverAFrontCamera() {
        // "rear" and "vue" spaced apart: the link streams from it, so it
        // must stay choosable as the radar.
        assertTrue(DeviceNameMatcher.isRearAdvert("Varia Rear Vue"))
        assertFalse(DeviceNameMatcher.isFrontCamera("Varia Rear Vue"))
        assertFalse(DeviceNameMatcher.isFrontCamera("Rear Vue 2"))
        assertTrue(DeviceNameMatcher.isFrontCamera("Vue-123"))
    }

    @Test fun knownAccessoryTakesRadarsCamerasAndTheVendor() {
        for (
        name in listOf(
            "RearVue8",
            "RTL515 1234",
            "Varia Vue 84509",
            "Vue-123",
            "Garmin Edge",
        )
        ) {
            assertTrue("accessory matcher must accept $name", DeviceNameMatcher.isKnownAccessory(name))
        }
        assertFalse(DeviceNameMatcher.isKnownAccessory("Pixel Watch"))
        assertFalse(DeviceNameMatcher.isKnownAccessory("JBL Headphones"))
    }

    @Test fun aMissingOrEmptyNameIsNotARadarName() {
        assertFalse(DeviceNameMatcher.isRadarName(null))
        assertFalse(DeviceNameMatcher.isRadarName(""))
    }

    @Test fun unambiguousRadarMatchesOnRearvueTokenAlone() {
        // "RearVue8" satisfies isUnambiguousRadar ONLY through the
        // contains("rearvue") disjunct: it does not contain "rtl", and the
        // `varia && !vue` arm fails (no "varia"). Stripping the "rear" prefix
        // to plain "Vue8" removes the only matching token and must flip the
        // result to false - proving the rearvue disjunct is load-bearing and
        // catching a mutant that deletes or negates it.
        assertTrue(DeviceNameMatcher.isUnambiguousRadar("RearVue8"))
        assertFalse("without 'rear' the rearvue token no longer matches", DeviceNameMatcher.isUnambiguousRadar("Vue8"))
    }

    @Test fun rtlCountsOnlyBeforeADigit() {
        // A "Turtle Beach" headset paired would otherwise start a radar link.
        for (name in listOf("Turtle Beach Stealth", "Myrtle", "Heartland", "RTL")) {
            assertFalse(name, DeviceNameMatcher.isRearAdvert(name))
            assertFalse(name, DeviceNameMatcher.isRadarName(name))
            assertFalse(name, DeviceNameMatcher.isUnambiguousRadar(name))
            assertFalse(name, DeviceNameMatcher.isKnownAccessory(name))
        }
        for (name in listOf("RTL12345", "eRTL615", "eRTL-12345", "Varia RTL 515")) {
            assertTrue(name, DeviceNameMatcher.isRearAdvert(name))
            assertTrue(name, DeviceNameMatcher.isRadarName(name))
            assertTrue(name, DeviceNameMatcher.isUnambiguousRadar(name))
            assertTrue(name, DeviceNameMatcher.isKnownAccessory(name))
        }
    }

    @Test fun eBikeNameMatchesEitherHalfOfTheBikesName() {
        assertTrue(DeviceNameMatcher.isEBikeName("smart system eBike"))
        assertTrue(DeviceNameMatcher.isEBikeName("My EBIKE"))
        assertTrue(DeviceNameMatcher.isEBikeName("BOSCH SMART SYSTEM"))
        assertFalse(DeviceNameMatcher.isEBikeName("RearVue8"))
        assertFalse(DeviceNameMatcher.isEBikeName(null))
    }

    @Test fun matchingIsCaseInsensitive() {
        assertTrue(DeviceNameMatcher.isRearAdvert("REARVUE8"))
        assertTrue(DeviceNameMatcher.isRadarName("VARIA rtl515"))
        assertTrue(DeviceNameMatcher.isKnownAccessory("GARMIN"))
    }
}
