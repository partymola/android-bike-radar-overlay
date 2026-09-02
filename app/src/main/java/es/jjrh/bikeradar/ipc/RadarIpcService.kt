// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.DeviceNameMatcher
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.access.PrefsRadarGrantStore
import es.jjrh.bikeradar.access.StoredRadarAccessGate
import es.jjrh.bikeradar.access.SystemPackageIdentity
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The bound service another app connects to.
 *
 * A shell: it owns the binder's lifetime and the loop that feeds it, and
 * nothing else. Every decision about who may read or act lives in
 * [RadarIpcBinder] and the access gate behind it, and is tested there;
 * `RadarIpcServiceTest` and `RadarIpcServiceManifestTest` cover only what is
 * true here, which is the lifecycle and the exported surface.
 *
 * Binding is not authorisation. Any app holding the manifest permission can
 * bind - it is `normal`, so it is granted at install to anything that asks -
 * and will then be refused by every method that returns data or touches
 * hardware until the rider grants it on the consent screen. The permission is
 * a coarse filter, never the gate.
 *
 * Independent of the foreground ride service on purpose: a consumer binding
 * here must not start a ride, and a ride ending must not disconnect a consumer
 * that is only asking whether a radar is connected.
 */
class RadarIpcService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val binder: RadarIpcBinder by lazy {
        val store = PrefsRadarGrantStore(
            getSharedPreferences(PrefsRadarGrantStore.PREFS_NAME, Context.MODE_PRIVATE),
        )
        // One resolver, so the gate and the binder can never disagree about who
        // a uid belongs to.
        val packages = SystemPackageIdentity(packageManager)
        // Hoisted: the first read of a prefs file is disk I/O, and this lambda
        // answers on a binder pool thread a consumer is blocked on.
        val prefs = Prefs(this)
        RadarIpcBinder(
            gate = StoredRadarAccessGate(
                store = store,
                identity = packages,
                now = { System.currentTimeMillis() },
            ),
            identity = packages,
            radarState = { RadarStateBus.state.value },
            batteryPercent = {
                // The contract promises the PRIMARY radar. The rider's pin is
                // what makes that exact; without one, and with two radars
                // bonded, this answers whichever the map happens to yield
                // first, so the AIDL scopes the promise to the pinned case.
                val entries = BatteryStateBus.entries.value
                val pinned = prefs.radarMac
                    ?.let { BikeRadarService.macToSlug[it] ?: BikeRadarService.macToSlug[it.uppercase(Locale.ROOT)] }
                    ?.let { entries[it] }
                (pinned ?: entries.values.firstOrNull { DeviceNameMatcher.isRadarName(it.name) })?.pct
            },
            setLightMode = { RadarControlBridge.set(it) },
            markUsed = { store.markUsed(it, System.currentTimeMillis()) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // collectLatest, not collect: a consumer slow to be handed a frame
        // should get the newest one rather than a queue of stale positions.
        // Every snapshot is the whole picture, so a skipped one loses nothing.
        scope.launch {
            RadarStateBus.state.collectLatest { survive("feed") { binder.broadcast(it) } }
        }
        // A grant is checked once per registration and then held, so something
        // has to invalidate it when the rider changes their mind. Without this
        // a revoked app keeps receiving frames until it happens to unbind.
        scope.launch {
            PrefsRadarGrantStore.writes.collect { survive("revalidation") { binder.revalidate() } }
        }
    }

    /**
     * Run one turn of a collector so that a throw cannot end the collector.
     *
     * A throw escaping a `collect` body cancels that coroutine, and the
     * SupervisorJob then keeps the service alive with nothing to restart it.
     * The two outcomes are the ones this whole surface is built to prevent:
     * every consumer's stream stops for good, or revalidation stops and an app
     * the rider revoked carries on receiving frames. Neither raises anything -
     * the exception dies with the Job, so a rider reports "it just stopped" and
     * the log holds nothing.
     *
     * Caught here rather than at the scope, because a `CoroutineExceptionHandler`
     * reports the failure and still leaves the collector dead.
     *
     * Deliberately catching `Throwable`: the point is that the loop outlives
     * whatever went wrong, and narrowing it to the causes we can currently name
     * would restore the silent death for the ones we cannot.
     *
     * `internal` so a test can drive a throw through it directly. Nothing that
     * a Robolectric test can reach makes `broadcast` or `revalidate` throw, so
     * the alternative was a fix with no failing test behind it.
     * `RadarIpcServiceCollectorsSurviveTest` covers both halves: that this
     * swallows and logs, and that both collectors are wrapped in it.
     */
    internal fun survive(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "cross-app $what failed; the loop continues", t)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Bind-only, so a start is answered by ending it.
     *
     * This service is exported behind an install-granted permission, so any app
     * can call `startService` on it. The default `START_STICKY` would then have
     * Android bring it back after a kill with no client at all - collectors
     * running, `onUnbind` never firing, registrations never released. The
     * `stopSelf` is the other half and the one that closes the live case rather
     * than the restart: without it a single start pins the service for as long
     * as the process lives. A bound client keeps it alive regardless, which is
     * why stopping here is safe.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Every consumer has gone. Nobody is left to lift an overlay hold, and
        // a rider left without their overlay has no way to work out why.
        //
        // Released, not killed. The instance survives an unbind, and a consumer
        // that binds in onStart and unbinds in onStop rebinds on every rotation
        // to the same lazily-built binder. `kill()` is permanent, so killing
        // here would refuse every later registration for the life of the
        // instance, with the consumer unable to tell that from "no grant".
        binder.releaseRegistrations()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        // Killed here, where the instance really is finished.
        binder.releaseAll()
        super.onDestroy()
    }

    companion object {
        /** What a consumer binds to. Kept here so the manifest and the docs agree. */
        const val ACTION = "es.jjrh.bikeradar.action.RADAR_SERVICE"

        private const val TAG = "BikeRadar.Ipc"
    }
}
