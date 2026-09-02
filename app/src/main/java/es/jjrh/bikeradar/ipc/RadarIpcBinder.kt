// SPDX-License-Identifier: GPL-3.0-or-later
// Additional permission for cross-app consumers: additional-permission.txt
package es.jjrh.bikeradar.ipc

import android.os.Binder
import android.os.RemoteCallbackList
import android.util.Log
import es.jjrh.bikeradar.RadarLightMode
import es.jjrh.bikeradar.RadarState
import es.jjrh.bikeradar.access.PackageIdentity
import es.jjrh.bikeradar.access.RadarAccessGate
import kotlinx.coroutines.CancellationException

/**
 * The cross-app contract, implemented.
 *
 * This IS the binder the framework hands to another app, not a testable core
 * behind one, so a test that constructs this exercises what ships.
 * [callingUid] is injected only because [Binder.getCallingUid] answers about
 * the real caller, which a unit test has no way to be.
 *
 * Every GATED method recomputes the rider's grant, so a revocation takes effect
 * on the next call. Three are ungated by design and the AIDL names them: the
 * version, unregistering, and showing the overlay again.
 *
 * The streaming path is the exception: a registration's grant is checked once
 * and then held, because resolving a package through the PackageManager at
 * radar cadence is not free. [revalidate] is what makes that safe.
 */
class RadarIpcBinder(
    private val gate: RadarAccessGate,
    private val identity: PackageIdentity,
    private val radarState: () -> RadarState,
    private val batteryPercent: () -> Int?,
    private val setLightMode: (RadarLightMode) -> Boolean,
    private val markUsed: (String) -> Unit,
    private val callingUid: () -> Int = { Binder.getCallingUid() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : IRadarService.Stub() {

    internal val listeners = object : RemoteCallbackList<IRadarListener>() {
        override fun onCallbackDied(callback: IRadarListener, cookie: Any?) {
            (cookie as? String)?.let { onConsumerDied(it) }
        }
    }

    /**
     * A consumer's process went away without unregistering, which is the
     * likeliest way a hold gets left behind: a crash is the one exit where the
     * consumer runs no code of its own.
     *
     * The death recipient fires only for a REMOTE binder, so the tests drive
     * [listeners]`.onCallbackDied` directly. That also exercises the
     * two-argument override, whose one-argument sibling compiles, silently
     * loses the cookie, and leaves every crashed consumer's hold in place.
     */
    internal fun onConsumerDied(packageName: String) = synchronized(broadcastLock) {
        // Under the same lock as the other drop-and-show paths, or a
        // `setOverlayVisible(false)` that has already passed its registration
        // check can install a hold after this lifts nothing.
        //
        // This is the one path that takes the lock in the opposite order: every
        // other takes broadcastLock and then enters RemoteCallbackList's own
        // monitor, while this is reached from inside it. Safe because
        // `binderDied` removes the callback inside that monitor and calls here
        // after the block closes, so nothing holds it while waiting for this.
        // That is framework behaviour, not ours, and no test drives it.
        RadarOverlayGate.show(packageName)
    }

    /**
     * `RemoteCallbackList` permits ONE broadcast at a time and throws on an
     * overlapping `beginBroadcast`. Every `beginBroadcast` here is inside this
     * lock, and they run on binder pool threads and Dispatchers.Default at
     * once, which a consumer connecting mid-ride reaches ordinarily.
     *
     * The throw would be silent and permanent: it cancels the collector and the
     * SupervisorJob keeps the scope alive, so the feed stops for every consumer,
     * or revalidation stops and a revoked app keeps receiving frames.
     *
     * [failing], [lastStamped] and [consumerIndices] are read and written under
     * it too, and every function touching them takes it itself rather than
     * trusting its caller, which is what makes that a property of those fields
     * instead of a habit. Delivery is `oneway`, so holding this across a
     * broadcast waits on nobody.
     */
    private val broadcastLock = Any()

    /**
     * Packages whose delivery is currently throwing, so [failed] logs the start
     * of a bad spell rather than every frame of it. Cleared on the next
     * delivery that succeeds and when a registration goes.
     */
    private val failing = mutableSetOf<String>()

    /**
     * When each package was last stamped as having read, so the frame feed does
     * not re-enter the grant store on every frame.
     */
    private val lastStamped = mutableMapOf<String, Long>()

    /**
     * Small stable numbers standing in for package names in the log lines. The
     * value derives from the map's own size, so a racing read-then-write hands
     * two packages one number and destroys the only thing it is for.
     */
    private val consumerIndices = mutableMapOf<String, Int>()

    /** Never gated: a consumer has to be able to tell whether it speaks our layout. */
    override fun getContractVersion(): Int = RadarContract.VERSION

    override fun registerTargetListener(listener: IRadarListener?): Boolean {
        if (listener == null) return false
        val pkg = callerPackageIfAllowedToRead() ?: return false
        // One live registration per package. A second replaces the first
        // rather than being refused: a consumer reconnecting after its own
        // crash would otherwise believe it had a stream and receive nothing.
        dropRegistrations(pkg)
        val registered = listeners.register(listener, pkg)
        // linkToDeath throws on an already-dead binder, so this fails in exactly
        // the reconnect-after-a-crash flow the replacement above is for, leaving
        // a hold with nothing to lift it. NOT pinned by any test and cannot be:
        // a unit test's listener is a local binder that never fails linkToDeath.
        if (!registered) RadarOverlayGate.show(pkg)
        return registered
    }

    override fun unregisterTargetListener(listener: IRadarListener?) {
        if (listener == null) return
        val pkg = callerPackage() ?: return
        // Ungated as to the GRANT, or a revoked consumer keeps a registration it
        // has no way to withdraw. Scoped to its OWN registration, so presenting
        // someone else's token deregisters nothing.
        //
        // IDENTITY IS THE BINDER, NEVER THE INTERFACE OBJECT. `asInterface`
        // mints a fresh `Stub.Proxy` per transaction, so a reference comparison
        // can only ever fail, and silently: unregister no-ops, the stream runs
        // on and the hold below never lifts. `IdentityOnlyListener` in the tests
        // is what makes that reachable, since a local listener is otherwise
        // passed straight through as the same object.
        //
        // The whole thing under one lock, not just the lookup: releasing it
        // between the ownership read and the unregister lets a concurrent
        // `setOverlayVisible(false)` anchor its hold to a listener that is
        // already gone.
        val token = listener.asBinder()
        synchronized(broadcastLock) {
            val n = listeners.beginBroadcast()
            val owner = try {
                (0 until n).firstOrNull { listeners.getBroadcastItem(it).asBinder() == token }
                    ?.let { listeners.getBroadcastCookie(it) as? String }
            } finally {
                listeners.finishBroadcast()
            }
            if (owner != pkg) return
            listeners.unregister(listener)
            RadarOverlayGate.show(pkg)
        }
    }

    override fun getBatteryPercent(): Int {
        if (callerPackageIfAllowedToRead() == null) return NO_READING
        return batteryPercent() ?: NO_READING
    }

    override fun isConnected(): Boolean {
        if (callerPackageIfAllowedToRead() == null) return false
        return RadarStateProjection.toParcel(radarState()).streamLive
    }

    override fun setRadarLightMode(mode: Int): Boolean {
        if (callerPackageIfAllowedToControl() == null) return false
        // Spelled out rather than an ordinal into RadarLightMode, so the enum
        // can be reordered without changing the wire. A mode with no value here
        // is unsettable rather than silently wrong.
        val wanted = when (mode) {
            RadarContract.LIGHT_MODE_NIGHT_FLASH -> RadarLightMode.NIGHT_FLASH
            RadarContract.LIGHT_MODE_DAY_FLASH -> RadarLightMode.DAY_FLASH
            RadarContract.LIGHT_MODE_SOLID -> RadarLightMode.SOLID
            RadarContract.LIGHT_MODE_PELOTON -> RadarLightMode.PELOTON
            RadarContract.LIGHT_MODE_OFF -> RadarLightMode.OFF
            else -> return false
        }
        return setLightMode(wanted)
    }

    override fun setOverlayVisible(visible: Boolean): Boolean {
        // Showing needs no grant at all. Restoring the rider's own safety
        // display is never a privileged act, and a rider who turns control off
        // while leaving read on must not strand a hold its owner can no longer
        // lift. Same reasoning as unregisterTargetListener, ungated for it.
        if (visible) {
            val pkg = callerPackage() ?: return false
            RadarOverlayGate.show(pkg)
            return true
        }
        val pkg = callerPackageIfAllowedToControl() ?: return false
        // Hiding requires a live registration, as a safety rule rather than a
        // convenience: the hold must be anchored to something that dies, and a
        // registered listener has a death recipient. Without it a consumer that
        // hid our display could crash and leave the rider with no collision
        // warning and nothing to restore it.
        //
        // The check and the hide are one step under the lock, which orders the
        // REGISTRATION check against the hide: a revoke landing between them
        // would drop the registration, lift nothing, and let this install a hold
        // owned by a package with neither grant nor registration.
        return synchronized(broadcastLock) {
            if (pkg !in registeredPackagesLocked()) {
                false
            } else {
                RadarOverlayGate.hide(pkg)
                true
            }
        }
    }

    /**
     * Push a snapshot to everyone registered. Called from the app's own scope
     * at radar cadence; [IRadarListener] is `oneway`, so a wedged consumer
     * cannot stall this loop.
     */
    fun broadcast(state: RadarState) {
        val delivered = synchronized(broadcastLock) {
            val n = listeners.beginBroadcast()
            try {
                // Projected only once there is somebody to project for. A bound
                // consumer the rider never granted costs a whole snapshot per
                // frame otherwise, for the length of a ride.
                if (n == 0) return
                val parcel = RadarStateProjection.toParcel(state)
                val now = clock()
                buildSet {
                    for (i in 0 until n) {
                        val pkg = listeners.getBroadcastCookie(i) as? String
                        // A consumer that died between beginBroadcast and here
                        // throws rather than returning; dropping it here would
                        // mutate the list mid-broadcast, so onCallbackDied removes.
                        //
                        // Only a delivery that SUCCEEDED counts as a read. A
                        // throw stamped as one would tell the rider, on the very
                        // screen built to show who is using their radar, that a
                        // wedged consumer read seconds ago.
                        runCatching { listeners.getBroadcastItem(i).onRadarState(parcel) }
                            .onSuccess {
                                failing.remove(pkg ?: UNKNOWN_CONSUMER)
                                if (pkg != null && now - (lastStamped[pkg] ?: 0L) >= STAMP_INTERVAL_MS) {
                                    lastStamped[pkg] = now
                                    add(pkg)
                                }
                            }
                            .onFailure { failed(pkg, it) }
                    }
                }
            } finally {
                listeners.finishBroadcast()
            }
        }
        // The stream never re-enters the gate, so without this the settings list
        // reports the consumer receiving most as the one gone quietest. The
        // store's own throttle applies only after it has parsed the whole grant
        // file, which here would be once per consumer per frame, so the decision
        // is taken above and the store's floor stays the durable one. Outside
        // the lock, because the store takes its own.
        delivered.forEach(markUsed)
    }

    /**
     * A consumer's delivery threw. Logged once per spell, not once per frame.
     *
     * A dead binder is expected and `onCallbackDied` cleans it up. What is not
     * is a consumer that stays registered and keeps throwing: a filled `oneway`
     * buffer raises without killing the binder, so it holds its registration
     * and its overlay hold while receiving nothing for the rest of the ride.
     *
     * The package is NOT printed. Which third-party apps a rider has is theirs,
     * and the grant store refuses to journal these names for the same reason. A
     * rider pasting logcat into an issue is disclosing those lines, not an
     * inventory of what they have installed. A per-process index goes out
     * instead, which tells two consumers apart and means nothing off the phone.
     */
    private fun failed(packageName: String?, cause: Throwable) {
        // Takes the lock itself rather than relying on its caller, which is what
        // makes the lock a property of the fields rather than a habit.
        val first = synchronized(broadcastLock) { failing.add(packageName ?: UNKNOWN_CONSUMER) }
        if (!first) return
        Log.w(TAG, "consumer ${consumerIndex(packageName)} stopped taking frames: $cause")
    }

    /**
     * A stable small number for a package, for the log lines. First-seen order,
     * never reused, so a maintainer can tell "one app is wedged" from "both
     * are". The mapping is in memory only, so it means nothing off the phone.
     *
     * Under the lock because `getOrPut` on a size-derived value is a
     * read-then-write, reached from the feed inside the lock and from
     * revalidation's failure branch outside it.
     */
    private fun consumerIndex(packageName: String?): Int = synchronized(broadcastLock) {
        consumerIndices.getOrPut(packageName ?: UNKNOWN_CONSUMER) { consumerIndices.size + 1 }
    }

    /**
     * Drop a package's registration and its overlay hold, for a grant the
     * rider has just revoked.
     *
     * The binder is deliberately left connected. Killing it looks like a crash
     * to the consumer, where a refused re-registration is a state it can
     * explain to its own user.
     *
     * Both steps under one lock, so a concurrent `setOverlayVisible(false)`
     * cannot pass its registration check here and install its hold after the
     * show. Kotlin's `synchronized` is reentrant, so the nested acquisition in
     * [dropRegistrations] is free.
     */
    fun revoke(packageName: String) = synchronized(broadcastLock) {
        dropRegistrations(packageName)
        RadarOverlayGate.show(packageName)
    }

    /**
     * Re-check every live registration against the store, and drop the ones
     * that no longer hold read.
     *
     * The grant is checked once at registration and then held, because
     * re-resolving a package through the PackageManager at radar cadence is not
     * free. This is what makes that safe: the service calls it on every write to
     * the grant store. Without it a revoked app keeps receiving frames until it
     * unbinds, which is the one case where "revoke" has to mean now.
     */
    fun revalidate() {
        // Holders as well as listeners. Every holder is already in the registry
        // today, so the union adds nothing; it is here because a future path
        // taking a hold without a registration would otherwise put it beyond the
        // rider's only remedy. `revalidateLiftsAHoldWithNoRegistration` pins it.
        val live = synchronized(broadcastLock) { registeredPackagesLocked() }
        for (pkg in live + RadarOverlayGate.hiddenBy.value) {
            // Per package, so one that throws cannot skip the rest: a turn
            // abandoned half way is a revoke that did not take effect and does
            // not run again until the next write to the store.
            runCatching {
                val uid = identity.uidOf(pkg)
                when {
                    // No uid means uninstalled, which is a revocation as far as
                    // a live registration is concerned.
                    uid == null || !gate.canRead(uid) -> revoke(pkg)
                    // Control can be dropped on its own: the consent screen
                    // takes the two answers separately and a rider can re-run
                    // it to change their mind. The hold needs control, so
                    // losing it must lift the hold even though the stream
                    // survives.
                    !gate.canControl(uid) -> RadarOverlayGate.show(pkg)
                }
            }.onFailure {
                // Rethrown rather than swallowed, matching the wrapper above:
                // teardown cancels this collector and absorbing that would
                // leave it running against a service that has gone.
                if (it is CancellationException) throw it
                Log.w(
                    TAG,
                    "could not re-check consumer ${consumerIndex(pkg)}'s grant, " +
                        "so a revoke may not have taken effect: $it",
                )
            }
        }
    }

    /**
     * Every consumer has unbound: drop the registrations and the holds, but
     * keep the list usable.
     *
     * `kill()` is deliberately NOT used here: it is permanent, and this binder
     * is reused, so a consumer that binds in `onStart` and unbinds in `onStop`
     * would find its stream refused after one rotation with no way to tell that
     * from having no grant.
     */
    fun releaseRegistrations() {
        val all = synchronized(broadcastLock) {
            val n = listeners.beginBroadcast()
            try {
                buildList { for (i in 0 until n) add(listeners.getBroadcastItem(i)) }
            } finally {
                listeners.finishBroadcast()
            }
        }
        all.forEach { listeners.unregister(it) }
        synchronized(broadcastLock) {
            failing.clear()
            lastStamped.clear()
        }
        RadarOverlayGate.reset()
    }

    /** For service teardown, where the instance itself is finished. */
    fun releaseAll() {
        synchronized(broadcastLock) {
            listeners.kill()
            failing.clear()
            lastStamped.clear()
        }
        RadarOverlayGate.reset()
    }

    /**
     * Forget what we were suppressing and throttling for a package that is no
     * longer registered.
     *
     * Without this a consumer that wedges, is dropped, comes back and wedges
     * again with no good frame in between is silent the second time. The index
     * is deliberately NOT cleared, so it stays the same across a ride's logs.
     */
    private fun forgetPerRegistrationState(packageName: String) = synchronized(broadcastLock) {
        failing.remove(packageName)
        lastStamped.remove(packageName)
    }

    private fun dropRegistrations(packageName: String) {
        val doomed = synchronized(broadcastLock) {
            // INSIDE the lock, not before it: `registerTargetListener` calls
            // this on a binder pool thread holding nothing, which is the
            // ordinary connect path, and the frame feed is in these same two
            // collections at that moment.
            forgetPerRegistrationState(packageName)
            val n = listeners.beginBroadcast()
            try {
                buildList {
                    for (i in 0 until n) {
                        if (listeners.getBroadcastCookie(i) == packageName) add(listeners.getBroadcastItem(i))
                    }
                }
            } finally {
                listeners.finishBroadcast()
            }
        }
        doomed.forEach { listeners.unregister(it) }
    }

    /**
     * Every package currently registered, read under [broadcastLock].
     *
     * Whether to hold the lock ACROSS the answer is the caller's business.
     * [setOverlayVisible] does, or a registration vanishing between the two
     * strands its hold. [revalidate] deliberately does not: a registration
     * arriving late has already checked its own grant, and one leaving late
     * gets a harmless second revoke.
     */
    private fun registeredPackagesLocked(): Set<String> {
        val n = listeners.beginBroadcast()
        return try {
            buildSet {
                for (i in 0 until n) (listeners.getBroadcastCookie(i) as? String)?.let { add(it) }
            }
        } finally {
            listeners.finishBroadcast()
        }
    }

    private fun callerPackage(): String? = identity.resolve(callingUid())?.packageName

    private fun callerPackageIfAllowedToRead(): String? = callerPackage()?.takeIf { gate.canRead(callingUid()) }

    private fun callerPackageIfAllowedToControl(): String? = callerPackage()?.takeIf { gate.canControl(callingUid()) }

    companion object {
        /** Battery answer when refused, or when no radar has reported one. */
        const val NO_READING = -1

        private const val TAG = "BikeRadar.Ipc"

        /** Dedup key for a caller whose package could not be resolved. */
        private const val UNKNOWN_CONSUMER = ""

        /**
         * How often the stream re-stamps a consumer as having read.
         *
         * Set at the store's own floor, so this skips reads the store would
         * discard anyway. Nothing requires the two to agree and no test asserts
         * it: shorter here only costs the reads this exists to save, longer
         * only makes the stamp coarser than the store would. The settings list
         * renders an age rather than a moment, so a rider sees neither.
         */
        private const val STAMP_INTERVAL_MS = 60_000L
    }
}
