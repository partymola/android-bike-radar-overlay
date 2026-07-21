// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Pure, Android-free resolver for the location that feeds the sunrise/sunset
 * light auto-mode calc. Owns every step that turns rider input into a
 * trustworthy coordinate, so all of it is unit-testable in one place and the
 * Compose layer never constructs a coordinate itself.
 *
 * ## Why this exists
 * The rear/front light day-night auto-switch needs a local sunrise/sunset,
 * which needs a location. GPS (`ACCESS_COARSE_LOCATION`) is optional; a rider
 * who declines it used to fall back silently to London, which is wrong for
 * everyone else. This resolver adds a manual-coordinate path so a decliner can
 * still get accurate times, WITHOUT an approximate timezone/country guess -
 * a coordinate that is hours wrong in wide zones would be false precision and
 * against the app's honest-signal spirit. Two precise options, London only as
 * an honest last resort.
 *
 * ## Precedence (highest first)
 *   1. valid MANUAL coordinates (rider typed them)
 *   2. GPS last-known fix (permission granted)
 *   3. London fallback
 * MANUAL beats GPS on purpose: it is an explicit override the rider set.
 *
 * ## Input safety
 * The rider-facing text field is defended in depth. [sanitizeCoordinateInput]
 * runs on every text change (typed, pasted, autofilled, IME, voice) over the
 * WHOLE candidate value - there is no separate paste path - normalising the
 * locale decimal comma (es keyboards emit `,`) and the Unicode minus, then
 * dropping anything outside `optional leading '-' + digits + one '.'` and
 * capping length. [parseCoordinate] + [validManualLocation] are the commit
 * gate (finite + in range + both-or-neither), and [resolve] re-validates
 * whatever it is handed, so a corrupt pref, a bad migration, or a future
 * caller can never feed garbage into the solar math.
 */
object RideLocationResolver {

    const val LAT_MIN = -90.0
    const val LAT_MAX = 90.0
    const val LON_MIN = -180.0
    const val LON_MAX = 180.0

    /** Cap on a single coordinate field. `-179.9999999` is 12 chars; anything
     *  longer is beyond meaningful precision and just an attack surface. */
    const val MAX_COORD_INPUT_LEN = 12

    enum class Source { MANUAL, GPS, LONDON }

    data class Resolved(val lat: Double, val lon: Double, val source: Source)

    /**
     * Filter a candidate text-field value down to the allowed coordinate
     * charset. Runs on every `onValueChange` over the full value (so paste is
     * covered identically to typing). Normalises a Unicode minus (U+2212) to
     * ASCII `-` and a decimal comma to `.` first, then keeps only a single
     * leading `-`, digits, and a single `.`, and truncates to
     * [MAX_COORD_INPUT_LEN]. Never throws; returns the cleaned string (may be
     * empty).
     */
    fun sanitizeCoordinateInput(raw: String): String {
        val sb = StringBuilder(raw.length.coerceAtMost(MAX_COORD_INPUT_LEN))
        var seenDot = false
        for (ch in raw) {
            val c = when (ch) {
                '−' -> '-' // Unicode MINUS SIGN -> ASCII hyphen-minus
                ',' -> '.' // locale decimal separator (es) -> canonical dot
                else -> ch
            }
            when {
                c == '-' && sb.isEmpty() -> sb.append('-')
                c == '.' && !seenDot -> {
                    sb.append('.')
                    seenDot = true
                }
                c in '0'..'9' -> sb.append(c)
                else -> {} // drop letters, symbols, extra signs/dots, whitespace
            }
            if (sb.length >= MAX_COORD_INPUT_LEN) break
        }
        return sb.toString()
    }

    /**
     * Parse a rider-entered coordinate string to a finite [Double], or null if
     * it is blank or not a finite number. Applies the same comma/Unicode-minus
     * normalisation as [sanitizeCoordinateInput] so a value that reached this
     * function by some path other than the field (paste-into-uncleaned,
     * restore) is still handled. Does NOT range-check - that is
     * [validManualLocation]'s job.
     */
    fun parseCoordinate(raw: String): Double? {
        val normalised = raw.trim().replace('−', '-').replace(',', '.')
        if (normalised.isEmpty() || normalised == "-" || normalised == "." || normalised == "-.") return null
        val d = normalised.toDoubleOrNull() ?: return null
        return if (d.isFinite()) d else null
    }

    /**
     * The valid manual location, or null. Enforces both-or-neither (a single
     * coordinate is not a point), finiteness, and range. Callers store only
     * what this accepts; [resolve] also runs it defensively on read.
     */
    fun validManualLocation(lat: Double?, lon: Double?): Pair<Double, Double>? {
        if (lat == null || lon == null) return null
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat !in LAT_MIN..LAT_MAX) return null
        if (lon !in LON_MIN..LON_MAX) return null
        return lat to lon
    }

    /** True when [lat] is a finite in-range latitude. */
    fun isValidLat(lat: Double?): Boolean = lat != null && lat.isFinite() && lat in LAT_MIN..LAT_MAX

    /** True when [lon] is a finite in-range longitude. */
    fun isValidLon(lon: Double?): Boolean = lon != null && lon.isFinite() && lon in LON_MIN..LON_MAX

    /**
     * Toggle the leading sign of a coordinate field's text. Backs the sign
     * button in the entry dialog so a minus is always reachable: many soft
     * keyboards (Compose's `KeyboardType.Decimal` among them) omit the minus
     * key, which would otherwise lock out every rider west of Greenwich or
     * south of the equator.
     */
    fun toggleSign(text: String): String = if (text.startsWith("-")) text.substring(1) else "-$text"

    /**
     * Format a stored coordinate for prefilling the entry field: fixed-point,
     * never scientific notation. `Double.toString()` switches to scientific
     * form below ~1e-3 (e.g. `5.0E-4`), whose `E`/`-` would be stripped by
     * [sanitizeCoordinateInput] and silently corrupt a near-zero coordinate on
     * re-edit. Rounded to 6 decimals (~0.1 m, far finer than sunrise needs),
     * trailing zeros trimmed.
     */
    fun formatCoordinate(value: Double): String {
        if (!value.isFinite()) return ""
        val plain = java.math.BigDecimal(value)
            .setScale(6, java.math.RoundingMode.HALF_UP)
            .toPlainString()
        val trimmed = plain.trimEnd('0').trimEnd('.')
        return if (trimmed.isEmpty() || trimmed == "-") "0" else trimmed
    }

    /**
     * Resolve the coordinate to use, applying the precedence chain and
     * re-validating every input (nothing here trusts stored/injected values).
     * Always returns a usable location; [Resolved.source] lets callers log
     * provenance without logging the coordinate.
     */
    fun resolve(
        manualLat: Double?,
        manualLon: Double?,
        gpsFix: Pair<Double, Double>?,
    ): Resolved {
        validManualLocation(manualLat, manualLon)?.let {
            return Resolved(it.first, it.second, Source.MANUAL)
        }
        if (gpsFix != null) {
            validManualLocation(gpsFix.first, gpsFix.second)?.let {
                return Resolved(it.first, it.second, Source.GPS)
            }
        }
        return Resolved(SunsetCalculator.LONDON_LAT_DEG, SunsetCalculator.LONDON_LON_DEG, Source.LONDON)
    }
}
