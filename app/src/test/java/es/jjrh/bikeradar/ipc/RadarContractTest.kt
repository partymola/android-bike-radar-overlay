// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The constants a consumer copies.
 *
 * Expected values are literals. A consumer reads these once and trusts them for
 * the life of its install, so asserting one against itself would stay green
 * through exactly the change that breaks every shipped client.
 *
 * The ACTION and PERMISSION constants are checked against the manifest by
 * `RadarIpcServiceManifestTest`, which resolves them through the PackageManager
 * rather than reading the XML as text.
 */
class RadarContractTest {

    @Test
    fun theContractVersionIsALiteral() {
        assertEquals(1, RadarContract.VERSION)
    }

    @Test
    fun theCapabilityBitsAreDistinctAndDoNotOverlap() {
        val bits = listOf(
            RadarContract.HAS_CLOSING_SPEED,
            RadarContract.HAS_LATERAL,
            RadarContract.HAS_RIDER_SPEED,
            RadarContract.HAS_VEHICLE_SIZE,
        )
        assertEquals(listOf(1, 2, 4, 8), bits)
        assertEquals("no two capabilities may share a bit", 15, bits.reduce { a, b -> a or b })
    }

    @Test
    fun everyVehicleClassCodeIsFixedAndDistinct() {
        val codes = listOf(
            RadarContract.RADAR_SIZE_CAR,
            RadarContract.RADAR_SIZE_TRUCK,
            RadarContract.RADAR_SIZE_BIKE,
        )
        assertEquals(listOf(0, 1, 2), codes)
    }

    @Test
    fun everyLightModeWireValueIsFixedAndDistinct() {
        val wire = listOf(
            RadarContract.LIGHT_MODE_NIGHT_FLASH,
            RadarContract.LIGHT_MODE_DAY_FLASH,
            RadarContract.LIGHT_MODE_SOLID,
            RadarContract.LIGHT_MODE_PELOTON,
            RadarContract.LIGHT_MODE_OFF,
        )
        assertEquals(listOf(0, 1, 2, 3, 4), wire)
    }

    @Test
    fun everyConsentWireValueIsFixed() {
        // A consumer app copies these numbers and strings rather than importing
        // them, so changing one silently breaks an install already shipping.
        // Distinct values matter as much as fixed ones: a consumer retries
        // RESULT_RIDE_IN_PROGRESS and must not retry RESULT_CALLER_UNKNOWN, so
        // collapsing the two would turn a permanent refusal into a loop.
        assertEquals("es.jjrh.bikeradar.action.REQUEST_RADAR_ACCESS", RadarContract.Consent.ACTION)
        assertEquals("es.jjrh.bikeradar.extra.READ", RadarContract.Consent.EXTRA_READ)
        assertEquals("es.jjrh.bikeradar.extra.CONTROL", RadarContract.Consent.EXTRA_CONTROL)
        assertEquals(1, RadarContract.Consent.RESULT_RIDE_IN_PROGRESS)
        assertEquals(2, RadarContract.Consent.RESULT_CALLER_UNKNOWN)
        assertEquals(3, RadarContract.Consent.RESULT_NOT_STORED)
    }

    @Test
    fun theBindStringsAreTheOnesConsumersHaveAlreadyCopied() {
        // Literals, because a consumer's own manifest and intent carry this
        // text. A rename that moved these constants and our manifest together
        // would satisfy every comparison between the two and still stop every
        // installed app from binding.
        assertEquals("es.jjrh.bikeradar", RadarContract.PACKAGE)
        assertEquals("es.jjrh.bikeradar.action.RADAR_SERVICE", RadarContract.ACTION)
        assertEquals("es.jjrh.bikeradar.permission.RADAR", RadarContract.PERMISSION)
    }

    @Test
    fun theAdvertisedPackageIsWhatTheReleaseBuildInstallsAs() {
        // Read off the build file, not BuildConfig: under the test variant that
        // answers for whichever variant is running, and this is a promise about
        // the release install a consumer binds to. A suffix on the release type
        // is the case that breaks every consumer, and it is invisible from here
        // at runtime.
        val gradle = RepoFiles.moduleBuildFile().readText()
        assertTrue(
            "applicationId and RadarContract.PACKAGE have drifted apart",
            gradle.contains("""applicationId = "${RadarContract.PACKAGE}""""),
        )

        // `signingConfigs` declares a debug block of its own earlier in the
        // file, so the closing anchor has to be looked for after the opening
        // one rather than from the top.
        val open = gradle.indexOf("        release {")
        val close = gradle.indexOf("""        getByName("debug")""", startIndex = open + 1)
        assertTrue("the release build type moved; this check cannot see it", open in 0 until close)
        assertFalse(
            "a suffix on the release build type makes RadarContract.PACKAGE unbindable",
            gradle.substring(open, close).contains("applicationIdSuffix"),
        )
    }
}
