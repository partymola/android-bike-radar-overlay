// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Running snapshot of eBike live-data fields, merged from the proprietary
 * status stream by [EBikeStatusDecoder]. Nullable means "not yet observed";
 * the decoder preserves any field absent from the current frame. Consumed by
 * the SYSTEM-card eBike row (battery), the AlertDecider stationary override,
 * [ClimbDetector], the walk-away arming gate and [RideEdgeDetector].
 *
 * (Field semantics originate in Bosch's public Live Data definition; the
 * proprietary status channel we actually read carries the same quantities.)
 */
data class LiveDataSnapshot(
    /** Speed, raw 1/100 km/h. Compute m/s as `raw / 360f`. */
    val speedRaw: Int? = null,
    /** Cadence, rpm. */
    val cadence: Int? = null,
    /** Rider (human) power, watts - NOT motor assist. */
    val riderPower: Int? = null,
    /** Motor assist power, watts. The complement of [riderPower]: total
     *  pedal-effort wattage = [riderPower] + [motorPower]. */
    val motorPower: Int? = null,
    /** Assist-mode enum (raw). Best-guess Bosch smart-system mapping per
     *  public docs (varies by drive-unit generation) is 0=Off, 1=Eco, 2=Tour,
     *  3=eMTB/Tour+, 4=Turbo - PENDING ride confirmation. Stored raw so
     *  downstream can map without changing the decoder. */
    val assistMode: Int? = null,
    /** Configured wheel circumference, millimetres (matches Bosch Live Data
     *  spec; ~2200 for a typical 700c). */
    val wheelCircumferenceMm: Int? = null,
    /** Ambient brightness, raw 1/1000 lux. */
    val ambientBrightnessRaw: Int? = null,
    /** Drive-system battery, percent 0-100. */
    val batterySoc: Int? = null,
    /** Current time, seconds since epoch (NOT ms). */
    val timeSec: Long? = null,
    /** Total distance, raw metres (NOT km). Log delta-since-session-start
     *  only; absolute odometer is rider-identifying under GDPR Recital 30. */
    val odometerM: Long? = null,
    /** Light state: 0=invalid, 1=off, 2=on. */
    val bikeLight: Int? = null,
    /** True when the eBike's anti-theft lock is engaged. */
    val systemLocked: Boolean? = null,
    /** True when the mains charger is plugged in. */
    val chargerConnected: Boolean? = null,
    /** True when the bike has cut headlight power to protect the drive
     *  battery (rider just lost their primary front light). */
    val lightReserve: Boolean? = null,
    /** True when a dealer service tool is connected. */
    val diagnosisActive: Boolean? = null,
    /** True when the wheel is at rest. Ground-truth standstill. */
    val bikeNotDriving: Boolean? = null,
)
