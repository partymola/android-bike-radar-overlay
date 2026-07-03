// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Firmware-keyed lateral (rangeX) correction for radar firmware versions
 * with a known sideways reporting bias.
 *
 * Radar firmware 6.70 reports directly-following vehicles ~1.2-1.5 m LEFT
 * of the bike centreline where earlier firmware read them centred
 * (follower-track medians across ride captures: -1.50 m and -1.20 m on
 * consecutive days; a physical mount realignment between the two changed
 * nothing, and the mount has no lateral play, so the shift is in the
 * firmware's lateral channel, not the installation). No firmware downgrade
 * path exists, so the bias is corrected app-side, keyed on the firmware
 * revision string the unlock sequence already reads from the standard
 * Device Information Service on every connection.
 *
 * The correction composes with the rider's mount-offset setting
 * ([es.jjrh.bikeradar.data.Prefs.radarLateralOffsetCm]) - both are pure
 * rangeX translations applied in [RadarV2Decoder] - and is deliberately a
 * separate mechanism: the mount slider describes where the radar sits
 * (centimetres), this table describes what the firmware misreports
 * (metres), and a rider clearing one must not lose the other.
 *
 * Matching is by version prefix so sub-revision strings ("6.70.0.x")
 * inherit their base entry. Unknown or unread firmware corrects nothing -
 * fail neutral.
 */
object FirmwareLateralCorrection {

    /** Correction (cm, positive = shift targets right) for the given
     *  firmware revision string, or 0 when the firmware is unknown or has
     *  no known bias. */
    fun correctionCm(firmwareRev: String?): Int {
        val rev = firmwareRev?.trim() ?: return 0
        return when {
            // Exact version or its sub-revisions ("6.70.0.x"); a bare
            // prefix would also swallow a hypothetical 6.700.
            rev == "6.70" || rev.startsWith("6.70.") -> CORRECTION_670_CM
            else -> 0
        }
    }

    /** The decoder-facing composition: the rider's mount offset plus the
     *  firmware correction (when enabled). Pure so the composition rule -
     *  disabling the firmware fix must not touch the mount offset and
     *  vice versa - is pinned by unit tests rather than living inline in
     *  the BLE controller. */
    fun effectiveLateralOffsetCm(mountOffsetCm: Int, firmwareRev: String?, correctionEnabled: Boolean): Int = mountOffsetCm + if (correctionEnabled) correctionCm(firmwareRev) else 0

    /** +1.35 m for firmware 6.70: the midpoint of the measured follower
     *  bias band (-1.50/-1.20 m medians on consecutive capture days).
     *  Provisional until more post-6.70 rides tighten the estimate;
     *  adjust here only, never by abusing the mount-offset setting. */
    const val CORRECTION_670_CM = 135
}
