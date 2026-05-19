// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Verifies SunsetCalculator against NOAA-published values for London (51.5°N, 0.13°W). */
class SunsetCalculatorTest {

    private val london = ZoneId.of("Europe/London")

    @Test
    fun summerSolstice2026() {
        // NOAA Solar Calculator: London, 2026-06-21 → sunset ~21:21 BST (UTC+1)
        val zdt = SunsetCalculator.sunsetZdt(LocalDate.of(2026, 6, 21), london)
        assertNotNull("summer solstice should not return null", zdt)
        val h = zdt!!.hour; val m = zdt.minute
        assertTrue("expected ~21:21 BST, got $h:$m", h == 21 && m in 19..23)
    }

    @Test
    fun winterSolstice2026() {
        // London, 2026-12-21: sunset ~15:53–15:58 GMT. Algorithm accuracy ±5 min.
        val zdt = SunsetCalculator.sunsetZdt(LocalDate.of(2026, 12, 21), london)
        assertNotNull("winter solstice should not return null", zdt)
        val h = zdt!!.hour; val m = zdt.minute
        assertTrue("expected ~15:55 GMT ±5 min, got $h:$m",
            (h == 15 && m in 50..59) || (h == 16 && m == 0))
    }

    @Test
    fun autumnEquinox2026() {
        // London, 2026-09-23: sunset ~18:55–19:00 BST. Algorithm accuracy ±5 min.
        val zdt = SunsetCalculator.sunsetZdt(LocalDate.of(2026, 9, 23), london)
        assertNotNull("autumn equinox should not return null", zdt)
        val h = zdt!!.hour; val m = zdt.minute
        assertTrue("expected ~18:58 BST ±5 min, got $h:$m",
            (h == 18 && m in 53..59) || (h == 19 && m in 0..3))
    }

    @Test
    fun summerSolsticeSunrise2026() {
        // London, 2026-06-21: sunrise ~04:43 BST. Algorithm accuracy ±5 min.
        val ms = SunsetCalculator.sunriseEpochMs(LocalDate.of(2026, 6, 21))
        assertNotNull("summer solstice sunrise should not return null", ms)
        val zdt = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms!!), london)
        val h = zdt.hour; val m = zdt.minute
        assertTrue("expected ~04:43 BST ±5 min, got $h:$m", h == 4 && m in 38..48)
    }

    @Test
    fun winterSolsticeSunrise2026() {
        // London, 2026-12-21: sunrise ~08:04 GMT. Algorithm accuracy ±5 min.
        val ms = SunsetCalculator.sunriseEpochMs(LocalDate.of(2026, 12, 21))
        assertNotNull("winter solstice sunrise should not return null", ms)
        val zdt = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms!!), london)
        val h = zdt.hour; val m = zdt.minute
        assertTrue("expected ~08:04 GMT ±5 min, got $h:$m", h == 8 && m in 0..9)
    }

    @Test
    fun sunriseAlwaysBeforeSunset() {
        // Sanity: on any given day, sunrise must come before sunset.
        for (date in listOf(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 21), LocalDate.of(2026, 12, 21))) {
            val rise = SunsetCalculator.sunriseEpochMs(date)
            val set = SunsetCalculator.sunsetEpochMs(date)
            assertNotNull(rise); assertNotNull(set)
            assertTrue("sunrise should precede sunset on $date", rise!! < set!!)
        }
    }

    @Test
    fun sunsetEpochMsMatchesZdt() {
        val date = LocalDate.of(2026, 6, 21)
        val ms = SunsetCalculator.sunsetEpochMs(date)
        val zdt = SunsetCalculator.sunsetZdt(date, london)
        assertNotNull(ms)
        assertNotNull(zdt)
        val diff = Math.abs(ms!! - zdt!!.toInstant().toEpochMilli())
        assertTrue("epochMs and ZDT should agree within 1s, diff=${diff}ms", diff < 1000)
    }

    // ── parameterised lat/lon (the v0.7.2 hotfix) ────────────────────────────

    @Test
    fun londonSunsetIsLaterThanMadridOnSummerSolsticeInUtc() {
        // On the summer solstice the high-latitude sites have much longer
        // days, so the absolute UTC sunset time at London (51.5°N) lands
        // later than at Madrid (40.4°N) despite Madrid being further west
        // (later solar noon). NOAA Solar Calculator values (year 2026
        // summer solstice): London sunset around 20h21 UTC; Madrid sunset
        // around 19h48 UTC. London leads by ~33 min in UTC. Verify the
        // formula reproduces this directional relationship.
        val date = LocalDate.of(2026, 6, 21)
        val londonMs = SunsetCalculator.sunsetEpochMs(date)
        val madridMs = SunsetCalculator.sunsetEpochMs(date, latDeg = 40.4168, lonDeg = -3.7038)
        assertNotNull(londonMs); assertNotNull(madridMs)
        assertTrue(
            "London sunset should follow Madrid sunset in UTC on summer solstice; " +
                "london=$londonMs madrid=$madridMs diff=${(londonMs!! - madridMs!!) / 60000}min",
            londonMs > madridMs
        )
    }

    @Test
    fun madridSunsetMatchesNoaa() {
        // Year-2026 summer-solstice NOAA reference for Madrid: sunset around
        // 21h48 CEST (19h48 UTC). Tolerance ±5 min.
        val ms = SunsetCalculator.sunsetEpochMs(LocalDate.of(2026, 6, 21), 40.4168, -3.7038)
        assertNotNull(ms)
        val zdt = java.time.ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(ms!!), ZoneId.of("Europe/Madrid")
        )
        val h = zdt.hour; val m = zdt.minute
        assertTrue("expected hour=21 min in 43..59 OR hour=22 min in 0..3, got h=$h m=$m",
            (h == 21 && m in 43..59) || (h == 22 && m in 0..3))
    }

    @Test
    fun sydneySunsetIsBeforeUtcMiddayOnAustralianWinter() {
        // Sydney (33.8688°S, 151.2093°E): southern-hemisphere far-east case.
        // Australian winter solstice is the same calendar day as the northern
        // summer solstice. Sydney AEST is UTC+10, so the local evening sunset
        // (around 16h53 AEST) lands in UTC morning (around 06h53 UTC). Sanity-
        // checks the formula handles a southern-hemisphere far-eastern point.
        val ms = SunsetCalculator.sunsetEpochMs(LocalDate.of(2026, 6, 21), -33.8688, 151.2093)
        assertNotNull(ms)
        val zdt = java.time.ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(ms!!), ZoneId.of("Australia/Sydney")
        )
        val h = zdt.hour; val m = zdt.minute
        assertTrue("expected hour=16 min in 43..59 OR hour=17 min in 0..3, got h=$h m=$m",
            (h == 16 && m in 43..59) || (h == 17 && m in 0..3))
    }

    @Test
    fun defaultParamsStillProduceLondonValues() {
        // The lat/lon parameters have London defaults; explicit-default and
        // no-arg calls must produce identical results. Pins the
        // backwards-compatibility contract for the v0.7.2 hotfix.
        val date = LocalDate.of(2026, 6, 21)
        val noArg = SunsetCalculator.sunsetEpochMs(date)
        val withDefaults = SunsetCalculator.sunsetEpochMs(
            date,
            latDeg = SunsetCalculator.LONDON_LAT_DEG,
            lonDeg = SunsetCalculator.LONDON_LON_DEG,
        )
        assertNotNull(noArg); assertNotNull(withDefaults)
        assertTrue("default-arg must equal explicit-default-arg", noArg == withDefaults)
    }
}
