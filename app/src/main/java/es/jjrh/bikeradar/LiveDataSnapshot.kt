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
    /** Selected assist-mode SLOT index (raw): 0=Off (fixed), 1-4 = the rider's
     *  four active slots in increasing-assistance order. NOT a fixed level: the
     *  Bosch smart system has 8 assist levels (Eco+, Eco, Tour, Tour+, Auto,
     *  Sport, eMTB, Turbo) and the rider configures WHICH 4 fill the slots via
     *  the Flow app, so this index is the display position, not the assist power
     *  behind it. Confirmed 0..4 on a bench cycle. Stored raw. */
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
    /** Light state from the proprietary status stream (obj 0x981c): 0=off, 1=on
     *  (binary). NB this is the proprietary-channel encoding, NOT the eb21 Live
     *  Data `0=invalid/1=off/2=on` - the stream we decode reports a plain 0/1
     *  (bench-confirmed). Stored raw; only the capture log reads it. */
    val bikeLight: Int? = null,
    /** True when the bike is locked OR asleep. The eBike (obj 0x808e) reports a
     *  single power/lock state - the anti-theft lock and plain idle-sleep both
     *  drive it true and drop the BLE link (only 0=active and 2=not-active seen;
     *  the decoder treats any non-zero as true). The bike never sleeps while
     *  moving, so "true => not being actively ridden" holds, which is how the
     *  walk-away arming and ride-confirmed gates consume it. A SOFT signal only,
     *  never a "rider has left" trigger - and consumers MUST age-gate it: a stale
     *  `false` must not be trusted (see [WalkAwayArmingGate], [RadarDropDecider]). */
    val systemLocked: Boolean? = null,
    /** True when the mains charger is plugged in. */
    val chargerConnected: Boolean? = null,
    /** True when the bike has cut headlight power to protect the drive
     *  battery (rider just lost their primary front light). */
    val lightReserve: Boolean? = null,
    /** True when a dealer service tool is connected. */
    val diagnosisActive: Boolean? = null,
    /** True when the wheel is at rest (obj 0x981a; 1=at rest, 0=moving).
     *  Tracks WHEEL ROTATION, not pedal/drive input: a no-pedal coast keeps it
     *  false (moving). Bench-confirmed - a ~1s wheel spin held it false through
     *  13-14s of free coast-down - so a downhill freewheel still reads "driving"
     *  and does NOT suppress alerts. Ground-truth standstill. */
    val bikeNotDriving: Boolean? = null,
)
