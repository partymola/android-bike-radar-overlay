// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/** Colour band for a battery percentage shown in the UI. */
enum class BatteryChipLevel { NORMAL, LOW, CRITICAL }

/**
 * Band a battery reading against the rider's own low-battery threshold.
 *
 * The chip used fixed 10 / 20 cuts while `batteryLowThresholdPct` is
 * rider-configurable and drives the in-ride low-battery cue. Set the threshold
 * to 30 and the cue fired while the chip still rendered neutral, so the number
 * the rider was warned about and the number on screen disagreed.
 *
 * [CRITICAL] is half the threshold, keeping the shape of the original bands
 * (10 was half of the default 20) rather than inventing a second setting.
 */
fun batteryChipLevel(pct: Int, lowThresholdPct: Int): BatteryChipLevel = when {
    pct <= lowThresholdPct / 2 -> BatteryChipLevel.CRITICAL
    pct <= lowThresholdPct -> BatteryChipLevel.LOW
    else -> BatteryChipLevel.NORMAL
}
