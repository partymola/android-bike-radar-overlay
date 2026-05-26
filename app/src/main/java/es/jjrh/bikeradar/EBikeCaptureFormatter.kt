// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import java.time.Instant

/**
 * Pure formatter that renders a [LiveDataSnapshot] into a single
 * capture-log line for post-ride correlation against radar / dashcam
 * events.
 *
 * Format (greppable, one key per non-null field):
 *
 *   `ebike spd_raw=1080 cad=85 power=120 batt=80 odo_delta_m=125 sysl=1 ...`
 *
 * Fields that have not been observed yet (still null on the snapshot)
 * are omitted entirely; this keeps the line short and avoids logging
 * synthetic zeros that would be hard to distinguish from real readings.
 *
 * Privacy hardening (see the [LiveDataSnapshot] field KDoc for the
 * underlying rationale):
 *
 *  - Odometer is logged as `odo_delta_m=<current - session-start>`,
 *    NEVER as an absolute. Absolute odometer is rider-identifying under
 *    GDPR Recital 30 (uniquely fingerprints the bike across rides).
 *  - Time is logged as ISO-8601 (`t_iso=...Z`), NEVER as raw epoch
 *    seconds. Both for grep-ability and to match the project-wide log
 *    convention.
 *
 * The formatter is stateless; the caller owns the session-start odometer
 * baseline and passes it in. On the first snapshot containing an
 * odometer, the caller is responsible for capturing the baseline before
 * the first delta is computed.
 */
object EBikeCaptureFormatter {

    /**
     * Render a snapshot to a one-line capture-log payload. Returns null
     * when every field is still unobserved (no useful information to log)
     * so the caller can skip the line entirely.
     *
     * @param snapshot Current merged snapshot from the eBike status reader.
     * @param sessionStartOdometerM Absolute odometer (metres) observed
     *   on the first snapshot of this session; the rest of the session
     *   logs `odometer - sessionStartOdometerM`. Null when no odometer
     *   has ever been observed.
     */
    fun format(snapshot: LiveDataSnapshot, sessionStartOdometerM: Long?): String? {
        val parts = ArrayList<String>(16)
        snapshot.speedRaw?.let { parts += "spd_raw=$it" }
        snapshot.cadence?.let { parts += "cad=$it" }
        snapshot.riderPower?.let { parts += "power=$it" }
        snapshot.ambientBrightnessRaw?.let { parts += "lux_raw=$it" }
        snapshot.batterySoc?.let { parts += "batt=$it" }
        snapshot.timeSec?.let { parts += "t_iso=${Instant.ofEpochSecond(it)}" }
        // Privacy rule: absolute odometer is rider-identifying. Log the
        // delta-since-session-start instead.
        snapshot.odometerM?.let { abs ->
            val baseline = sessionStartOdometerM ?: abs
            parts += "odo_delta_m=${abs - baseline}"
        }
        snapshot.bikeLight?.let { parts += "blight=$it" }
        snapshot.systemLocked?.let { parts += "sysl=${if (it) 1 else 0}" }
        snapshot.chargerConnected?.let { parts += "chg=${if (it) 1 else 0}" }
        snapshot.lightReserve?.let { parts += "lreserve=${if (it) 1 else 0}" }
        snapshot.diagnosisActive?.let { parts += "diag=${if (it) 1 else 0}" }
        snapshot.bikeNotDriving?.let { parts += "notdrv=${if (it) 1 else 0}" }
        if (parts.isEmpty()) return null
        return "ebike " + parts.joinToString(" ")
    }
}
