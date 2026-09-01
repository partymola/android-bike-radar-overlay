// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Live capability seam the IPC service reads, populated by [BikeRadarService]
 * on onCreate. Keeps [RadarIpcService] decoupled from the coordinator graph so
 * the bound service needs no context or direct references.
 */
object RadarIpcBridge {
    /** True while the rear-radar GATT link is open. */
    @Volatile
    var isRadarConnected: () -> Boolean = { false }

    /** Rear-radar battery percent, or null when unknown / not connected. */
    @Volatile
    var radarBatteryPercent: () -> Int? = { null }

    /** Issue a tail-light mode-set (contract int -> [RadarLightMode] mapping). */
    @Volatile
    var setRadarLightMode: (Int) -> Unit = {}

    /** Show/hide the on-screen radar overlay (currently not externally exposed). */
    @Volatile
    var setOverlayVisible: (Boolean) -> Unit = {}
}
