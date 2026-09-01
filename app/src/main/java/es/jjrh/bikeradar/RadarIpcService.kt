// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import es.jjrh.bikeradar.ipc.IRadarService
import es.jjrh.bikeradar.ipc.IRadarTargetListener
import es.jjrh.bikeradar.ipc.RADAR_SIZE_CAR
import es.jjrh.bikeradar.ipc.RADAR_SIZE_TRUCK
import es.jjrh.bikeradar.ipc.RadarStateParcel
import es.jjrh.bikeradar.ipc.RadarVehicleParcel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Exported, permission-gated bound service exposing live rear-radar state
 * over the agreed AIDL contract.
 *
 * Pushes [RadarStateParcel] snapshots to every registered listener at the
 * radar's own update cadence by collecting [RadarStateBus] (idle when no radar
 * frames arrive, so it costs nothing between connections). Battery and link
 * status come from [RadarIpcBridge], populated by [BikeRadarService].
 */
class RadarIpcService : Service() {

    private val listeners = RemoteCallbackList<IRadarTargetListener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var streamJob: Job? = null

    private val binder = object : IRadarService.Stub() {
        override fun getBatteryPercent(): Int = RadarIpcBridge.radarBatteryPercent() ?: -1

        override fun isConnected(): Boolean = RadarIpcBridge.isRadarConnected()

        override fun setRadarLightMode(mode: Int) {
            RadarIpcBridge.setRadarLightMode(mode)
        }

        override fun setOverlayVisible(visible: Boolean) {
            RadarIpcBridge.setOverlayVisible(visible)
        }

        override fun registerTargetListener(listener: IRadarTargetListener?): Boolean {
            if (listener == null) return false
            listeners.register(listener)
            return RadarIpcBridge.isRadarConnected()
        }

        override fun unregisterTargetListener(listener: IRadarTargetListener?) {
            if (listener == null) return
            listeners.unregister(listener)
        }
    }

    override fun onCreate() {
        super.onCreate()
        streamJob = scope.launch {
            RadarStateBus.state.collectLatest { state ->
                push(state)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = binder

    override fun onDestroy() {
        streamJob?.cancel()
        scope.cancel()
        listeners.kill()
        super.onDestroy()
    }

    private fun push(state: es.jjrh.bikeradar.RadarState) {
        val n = listeners.beginBroadcast()
        if (n == 0) {
            listeners.finishBroadcast()
            return
        }
        val parcel = RadarStateParcel(
            timestamp = state.timestamp,
            vehicles = state.vehicles.map { it.toParcel() }.toTypedArray(),
            bikeSpeedMs = state.bikeSpeedMs ?: 0f,
            isClear = state.isClear,
        )
        for (i in 0 until n) {
            runCatching { listeners.getBroadcastItem(i)?.onRadarState(parcel) }
        }
        listeners.finishBroadcast()
    }
}

private fun es.jjrh.bikeradar.Vehicle.toParcel(): RadarVehicleParcel = RadarVehicleParcel(
    id = id,
    distanceM = distanceM,
    closingKmh = closingKmh,
    size = when (size) {
        VehicleSize.TRUCK -> RADAR_SIZE_TRUCK
        else -> RADAR_SIZE_CAR
    },
    lateralPos = lateralPos,
    rangeXm = rangeXm,
    // Overlay's Vehicle.isBehind means "the target has passed the rider and is
    // now ahead" — that is exactly the contract's isAhead.
    isAhead = isBehind,
)
