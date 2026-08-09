// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/** Colour band for a battery percentage shown in the UI. */
enum class BatteryChipLevel { NORMAL, LOW, CRITICAL }

/**
 * Is this reading at or below the rider's own low-battery threshold?
 *
 * The single predicate behind both low-battery surfaces: the chip's colour
 * band and the overlay's low-battery marker. They are one question asked in
 * two places, so they read one function - a threshold of 20 means "warn me at
 * 20 percent", and a rider who sees an amber chip must also get the marker.
 * Holding the boundary in two expressions is what let them answer differently
 * at exactly the threshold.
 */
fun batteryIsLow(pct: Int, lowThresholdPct: Int): Boolean = pct <= lowThresholdPct

/**
 * Band a battery reading against the rider's own low-battery threshold.
 *
 * The chip used fixed 10 / 20 cuts while `batteryLowThresholdPct` is
 * rider-configurable and drives the in-ride low-battery marker. Set the
 * threshold to 30 and the marker showed while the chip still rendered
 * neutral, so the number the rider was warned about and the number on screen
 * disagreed.
 *
 * [CRITICAL] is half the threshold, keeping the shape of the original bands
 * (10 was half of the default 20) rather than inventing a second setting.
 */
fun batteryChipLevel(pct: Int, lowThresholdPct: Int): BatteryChipLevel = when {
    pct <= lowThresholdPct / 2 -> BatteryChipLevel.CRITICAL
    batteryIsLow(pct, lowThresholdPct) -> BatteryChipLevel.LOW
    else -> BatteryChipLevel.NORMAL
}

/**
 * Which devices the overlay should mark as low on battery.
 *
 * A reading only counts while it is recent enough to still describe the
 * device: an entry the app has not refreshed within [staleAfterMs] says
 * nothing about the battery now, and marking on it would be the same
 * stale-reading-as-live-claim the chips avoid.
 *
 * Extracted rather than inlined in the overlay loop so the boundary has a
 * test of its own. Inline, it sat inside a coroutine no unit test reaches,
 * which is how it came to disagree with [batteryIsLow] by one percent.
 */
fun lowBatterySlugs(
    entries: Collection<BatteryEntry>,
    lowThresholdPct: Int,
    nowMs: Long,
    staleAfterMs: Long,
): Set<String> = entries
    .filter { batteryIsLow(it.pct, lowThresholdPct) && nowMs - it.readAtMs < staleAfterMs }
    .map { it.slug }
    .toSet()
