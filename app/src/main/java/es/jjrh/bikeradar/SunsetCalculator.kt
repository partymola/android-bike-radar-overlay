// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Computes solar sunset for a fixed London location.
 *
 * Algorithm: NOAA Solar Calculator, https://www.esrl.noaa.gov/gmd/grad/solcalc/
 * (public domain). The formula series used here matches the NOAA spreadsheet
 * computations for solar zenith = 90.833° (geometric centre on the horizon with
 * standard atmospheric refraction).
 *
 * London coordinates are hardcoded; the feature is deliberately single-input.
 * Accuracy: within 1-2 minutes of tabulated NOAA values.
 */
object SunsetCalculator {

    private const val LONDON_LAT_DEG = 51.5074
    private const val LONDON_LON_DEG = -0.1278
    private const val ZENITH_DEG = 90.833

    /**
     * Returns the sunset instant for [date] in the given [zone] (default Europe/London).
     * The return value is an epoch millisecond timestamp.
     *
     * Returns null if the sun does not set on that date (polar night / midnight sun),
     * which cannot occur at London's latitude but is handled defensively.
     */
    fun sunsetEpochMs(date: LocalDate): Long? {
        val utcMinutes = sunsetUtcMinutes(date.year, date.monthValue, date.dayOfMonth)
            ?: return null
        val h = (utcMinutes / 60).toLong()
        val m = (utcMinutes % 60).toLong()
        val s = ((utcMinutes % 1) * 60).toLong()
        val utcDt = date.atTime(h.toInt().coerceIn(0, 23), m.toInt().coerceIn(0, 59), s.toInt().coerceIn(0, 59))
            .atZone(ZoneId.of("UTC"))
        return utcDt.toInstant().toEpochMilli()
    }

    /** Convenience: [ZonedDateTime] in [zone] for the sunset on [date]. */
    fun sunsetZdt(date: LocalDate, zone: ZoneId = ZoneId.of("Europe/London")): ZonedDateTime? {
        val ms = sunsetEpochMs(date) ?: return null
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
    }

    // ── NOAA formula chain ───────────────────────────────────────────────────

    private fun sunsetUtcMinutes(year: Int, month: Int, day: Int): Double? {
        val t = julianCentury(julianDay(year, month, day))
        val sunDecRad = Math.toRadians(sunDeclination(t))
        val latRad = Math.toRadians(LONDON_LAT_DEG)
        val zenRad = Math.toRadians(ZENITH_DEG)
        val cosHa = (Math.cos(zenRad) - Math.sin(latRad) * Math.sin(sunDecRad)) /
            (Math.cos(latRad) * Math.cos(sunDecRad))
        if (cosHa > 1.0 || cosHa < -1.0) return null
        val hourAngleDeg = Math.toDegrees(Math.acos(cosHa))
        val noonUtc = solarNoonUtcMinutes(t)
        return noonUtc + hourAngleDeg * 4.0
    }

    private fun solarNoonUtcMinutes(t: Double): Double =
        720.0 - 4.0 * LONDON_LON_DEG - equationOfTime(t)

    private fun julianDay(year: Int, month: Int, day: Int): Double {
        var y = year; var m = month
        if (m <= 2) { y--; m += 12 }
        val a = y / 100
        val b = 2 - a + a / 4
        return (365.25 * (y + 4716)).toLong() + (30.6001 * (m + 1)).toLong() + day + b - 1524.5
    }

    private fun julianCentury(jd: Double): Double = (jd - 2451545.0) / 36525.0

    private fun sunMeanLongitude(t: Double): Double =
        (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360.0

    private fun sunMeanAnomaly(t: Double): Double =
        357.52911 + t * (35999.05029 - 0.0001537 * t)

    private fun equationOfCenter(t: Double): Double {
        val mRad = Math.toRadians(sunMeanAnomaly(t))
        return Math.sin(mRad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            Math.sin(2.0 * mRad) * (0.019993 - 0.000101 * t) +
            Math.sin(3.0 * mRad) * 0.000289
    }

    private fun sunApparentLongitude(t: Double): Double {
        val omega = 125.04 - 1934.136 * t
        return sunMeanLongitude(t) + equationOfCenter(t) - 0.00569 -
            0.00478 * Math.sin(Math.toRadians(omega))
    }

    private fun obliquityCorrection(t: Double): Double {
        val e0 = 23.0 + (26.0 + (21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        return e0 + 0.00256 * Math.cos(Math.toRadians(125.04 - 1934.136 * t))
    }

    private fun sunDeclination(t: Double): Double =
        Math.toDegrees(
            Math.asin(
                Math.sin(Math.toRadians(obliquityCorrection(t))) *
                    Math.sin(Math.toRadians(sunApparentLongitude(t)))
            )
        )

    private fun equationOfTime(t: Double): Double {
        val eps = Math.toRadians(obliquityCorrection(t))
        val l0r = Math.toRadians(sunMeanLongitude(t))
        val ecc = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mr = Math.toRadians(sunMeanAnomaly(t))
        val y = Math.tan(eps / 2.0).let { it * it }
        return 4.0 * Math.toDegrees(
            y * Math.sin(2.0 * l0r) -
                2.0 * ecc * Math.sin(mr) +
                4.0 * ecc * y * Math.sin(mr) * Math.cos(2.0 * l0r) -
                0.5 * y * y * Math.sin(4.0 * l0r) -
                1.25 * ecc * ecc * Math.sin(2.0 * mr)
        )
    }
}
