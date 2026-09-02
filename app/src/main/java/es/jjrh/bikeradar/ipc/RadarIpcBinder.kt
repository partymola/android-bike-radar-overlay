// SPDX-License-Identifier: GPL-3.0-or-later
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
 * behind one: the checks that matter live in the same object that answers the
 * call, so a test that constructs this exercises what ships. [callingUid] is
 * injected only because [Binder.getCallingUid] answers about the real caller,
 * which a unit test has no way to be.
 *
 * Every GATED method recomputes the rider's grant, so a revocation takes effect
 * on the next call rather than at the next bind. Three are ungated by design
 * and the AIDL names them: the version, unregistering, and showing the overlay
 * again. None returns radar data or touches the hardware.
 *
 * The streaming path is the exception, and it is the highest-volume one: a
 * registration's grant is checked once and then held, because resolving a
 * package through the PackageManager at radar cadence is not free. [revalidate]
 * is what makes that safe, driven by every write to the grant store. Deleting
 * it would restore "a revoked app keeps receiving frames until it unbinds".
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
     * A consumer's process went away without unregistering.
     *
     * A crash is the likeliest way a hold gets left behind, since it is the one
     * exit where the consumer runs no code of its own.
     *
     * The death recipient fires only for a REMOTE binder, and a unit test's
     * listener is a local object that never dies - so the tests drive
     * [listeners]`.onCallbackDied` directly rather than calling this. That way
     * the two-argument override and the cookie cast are exercised too: the
     * one-argument overload also compiles, silently loses the cookie, and would
     * leave every crashed consumer's hold in place.
     */
    internal fun onConsumerDied(packageName: String) = synchronized(broadcastLock) {
        // Under the same lock as the other two drop-and-show paths. Without it
        // a `setOverlayVisible(false)` that has already passed its registration
        // check can install a hold AFTER this lifts nothing, leaving one owned
        // by a process that is already gone and has no death recipient left.
        // Safe against the other paths, which all take this lock and then the
        // callback list's: read off `RemoteCallbackList`, whose `binderDied`
        // removes the callback inside its own monitor and calls here after
        // that block closes, so nothing holds it while waiting for this.
        RadarOverlayGate.show(packageName)
    }

    /**
     * `RemoteCallbackList` is thread-safe for register and unregister, but it
     * permits ONE broadcast at a time and throws on an overlapping
     * `beginBroadcast`. Every `beginBroadcast` in this file is inside this
     * lock, and they run on two thread classes at once: the frame feed and the
     * revalidation collector on Dispatchers.Default, and every interface method
     * on a binder pool thread. A consumer connecting mid-ride is the ordinary
     * case, not an exotic one.
     *
     * The throw would be silent and permanent: it escapes `collectLatest` and
     * cancels that collector, and the SupervisorJob keeps the scope alive so
     * nothing restarts it. The feed would stop for every consumer, or
     * revalidation would stop and a revoked app would keep receiving frames -
     * which is the mechanism the grant store's KDoc cites to justify holding a
     * decision rather than re-checking it per frame.
     *
     * Delivery is `oneway`, so holding this across a broadcast does not wait on
     * any consumer.
     */
    private val broadcastLock = Any()

    /**
     * Packages whose delivery is currently throwing, so [failed] logs the start
     * of a bad spell rather than every frame of it. Cleared on the next
     * delivery that succeeds and when a registration goes, so a second spell is
     * reported as its own.
     *
     * Read and written under [broadcastLock], like the two below. Every
     * function that touches it takes the lock itself rather than trusting its
     * caller to hold one, which is what makes that a property of the field
     * instead of a habit; reentrancy makes it free where a caller already does.
     */
    private val failing = mutableSetOf<String>()

    /**
     * When each package was last stamped as having read, so the frame feed does
     * not re-enter the grant store on every frame.
     *
     * Read and written under [broadcastLock], like the one above and the one
     * below. Confinement to the feed coroutine would be enough today and is not
     * what this relies on: a second `broadcast` caller - a snapshot pushed on
     * registration, say, from a binder thread - would then be two threads in
     * one plain map with nothing failing to say so. The lock makes that
     * impossible rather than unlikely, and costs nothing, since only the
     * [markUsed] calls it decides on are kept outside.
     */
    private val lastStamped = mutableMapOf<String, Long>()

    /**
     * Small stable numbers standing in for package names in the log lines.
     *
     * Read and written under [broadcastLock], like the two above, and for a
     * sharper reason than either: the value is derived from the map's own size,
     * so a racing read-then-write hands two packages one number and destroys
     * the only thing the index is for.
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
        // linkToDeath throws on an already-dead binder, so this can fail in
        // exactly the reconnect-after-a-crash flow the replacement above is
        // for. The old listener is gone and the new one never linked, so a
        // surviving hold would have nothing left to lift it.
        //
        // NOT pinned by any test, and it cannot be from here: a unit test's
        // listener is a local binder in this process, which never fails
        // linkToDeath, so `register` always returns true and this branch is
        // unreachable. Stated rather than left looking covered.
        if (!registered) RadarOverlayGate.show(pkg)
        return registered
    }

    override fun unregisterTargetListener(listener: IRadarListener?) {
        if (listener == null) return
        val pkg = callerPackage() ?: return
        // Ungated as to the GRANT: a caller must always be able to stop, even
        // after the rider revokes, or a revoked consumer keeps a registration
        // it has no way to withdraw.
        //
        // Scoped to its OWN registration, and unconditionally - a caller with no
        // registration of its own must not be able to deregister someone else's
        // by presenting their token, and a caller that no-ops is what the
        // interface promises anyway.
        //
        // IDENTITY IS THE BINDER, NEVER THE INTERFACE OBJECT. `asInterface`
        // mints a fresh `Stub.Proxy` on every transaction for a remote caller,
        // so the object handed to this method is never the object the registry
        // holds and a reference comparison can only ever fail. It would fail
        // silently, too: unregister would no-op, the stream would run on and
        // the overlay hold below would never lift. `RemoteCallbackList` keys on
        // `asBinder()` for the same reason, so this matches what the unregister
        // one line down actually does. `IdentityOnlyListener` in the tests is
        // what makes that failure reachable from a unit test, where a local
        // listener is otherwise passed straight through as the same object.
        //
        // The whole thing under one lock, not just the lookup. Releasing it
        // between the ownership read and the unregister lets a concurrent
        // `setOverlayVisible(false)` see a registration this call is about to
        // remove, so its hold would be anchored to a listener that no longer
        // exists and no death recipient would ever lift it.
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
        return RadarContract.toParcel(radarState()).streamLive
    }

    override fun setRadarLightMode(mode: Int): Boolean {
        if (callerPackageIfAllowedToControl() == null) return false
        val wanted = RadarLightMode.entries.getOrNull(mode) ?: return false
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
        // Hiding requires a live registration, and that is a safety rule rather
        // than a convenience. The hold has to be anchored to something that
        // dies: a registered listener has a death recipient, so every way the
        // consumer can go away already lifts the hold. Without the anchor, a
        // consumer that hid our display and never registered could crash, or be
        // revoked, and leave the rider with no collision warning and nothing to
        // restore it. Hiding our display only makes sense while drawing your
        // own from our stream, which means reading it.
        //
        // The check and the hide are one step under the lock. The gate is
        // atomic on its own, so what this orders is the REGISTRATION check
        // against the hide: a revoke landing between them would drop the
        // registration, lift nothing (there is no hold yet), and then let this
        // hide install one owned by a package with neither a grant nor a
        // registration - the stranded state the anchor exists to prevent.
        // `revoke` and `unregisterTargetListener` take the same lock across
        // their own drop and show, so none of the three can interleave.
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
                val parcel = RadarContract.toParcel(state)
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
        // The stream is the loudest thing a granted app does and the only one
        // that never re-enters the gate, so without this the settings list
        // reports the consumer receiving most as the one gone quietest.
        //
        // The store's own throttle is applied AFTER it has read and parsed the
        // whole grant file, which on this path would be once per consumer per
        // frame for the length of a ride. Deciding here keeps the hot path off
        // it; the store's floor is still the durable one.
        //
        // Only the calls themselves are outside the lock, because the store
        // takes its own and there is no reason to hold both.
        delivered.forEach(markUsed)
    }

    /**
     * A consumer's delivery threw. Logged once per spell, not once per frame.
     *
     * A dead binder is expected and `onCallbackDied` cleans it up. What is not
     * is a consumer that stays registered and keeps throwing - a `oneway`
     * buffer that has filled raises without killing the binder, so the consumer
     * holds its registration and its overlay hold while receiving nothing for
     * the rest of the ride.
     *
     * The package is NOT printed, deliberately. Which third-party apps a rider
     * has is theirs, and the grant store already refuses to journal these names
     * for the same reason. The realistic channel here is a rider pasting a few
     * lines of logcat into an issue, where they are disclosing those lines and
     * have not agreed to an inventory of what they have installed.
     *
     * What is printed instead is a per-process index, which is the same trade
     * the overlay pipeline already makes when it logs a device's slug and not
     * its address: enough to tell two consumers apart and to link these two
     * lines to the same one, and nothing that means anything off the phone.
     */
    private fun failed(packageName: String?, cause: Throwable) {
        // Takes the lock itself rather than relying on its caller holding it.
        // Reentrancy makes that free from `broadcast`, and it is what lets
        // `failing`'s KDoc claim the lock as a property of the field rather
        // than a habit of whoever reaches it.
        val first = synchronized(broadcastLock) { failing.add(packageName ?: UNKNOWN_CONSUMER) }
        if (!first) return
        Log.w(TAG, "consumer ${consumerIndex(packageName)} stopped taking frames: $cause")
    }

    /**
     * A stable small number for a package, for the log lines.
     *
     * Assigned in first-seen order and never reused, so two lines about the
     * same consumer carry the same number for the life of the process and a
     * maintainer can tell "one app is wedged" from "both are". It carries no
     * name off the phone: the mapping is in memory only, so the number means
     * nothing to a reader who does not already know the rider's grants.
     *
     * Under the lock because `getOrPut` on a size-derived value is a
     * read-then-write, and this is reached both from the feed inside the lock
     * and from revalidation's failure branch outside it. A race would hand two
     * packages the same number, which is exactly the property above.
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
     * re-resolving a package through the PackageManager at radar cadence is
     * not free. That is only safe if something invalidates the held decision,
     * and this is it: the rider revoking in Settings is a write to the grant
     * store, and the service calls this on every such write.
     *
     * Without it a revoked app keeps receiving frames until it unbinds, which
     * is the one case where "revoke" has to mean now rather than eventually.
     */
    fun revalidate() {
        // Holders as well as listeners. Taking a hold requires a live
        // registration, so today every holder is already in the registry and
        // this union adds nothing - it is here because the rider's revoke is
        // the only remedy for a stranded hold, and a future path that takes one
        // without a registration would otherwise leave that remedy silently
        // unable to reach it. `revalidateLiftsAHoldWithNoRegistration` pins it.
        val live = synchronized(broadcastLock) { registeredPackagesLocked() }
        for (pkg in live + RadarOverlayGate.hiddenBy.value) {
            // Per package, so one that throws cannot skip the rest. The outer
            // wrapper in the service keeps the collector alive, but a turn
            // abandoned half way is a rider's revoke that did not take effect
            // and does not run again until the next write to the store.
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
     * `kill()` is deliberately NOT used here. It is permanent - `register`
     * refuses for the life of the object afterwards - and this binder is built
     * lazily and reused, so a consumer that binds in `onStart` and unbinds in
     * `onStop` would find its stream permanently refused after one rotation,
     * with no way to distinguish that from having no grant.
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
     * Without this the "once per spell" above is only true while a consumer
     * stays registered: one that wedges, is dropped, comes back and wedges
     * again without a single good frame in between would be silent the second
     * time. The index is deliberately NOT cleared, so a consumer that comes and
     * goes keeps the same number across a whole ride's logs.
     */
    private fun forgetPerRegistrationState(packageName: String) = synchronized(broadcastLock) {
        failing.remove(packageName)
        lastStamped.remove(packageName)
    }

    private fun dropRegistrations(packageName: String) {
        val doomed = synchronized(broadcastLock) {
            // INSIDE the lock, not before it. `revoke` already holds it and
            // reentrancy makes this free there, but `registerTargetListener`
            // calls this on a binder pool thread holding nothing - the ordinary
            // connect path - and the frame feed is in these same two
            // collections under the lock at that moment.
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
     * Holding the lock ACROSS the answer and what a caller does with it is the
     * caller's business, not this function's: [setOverlayVisible] does, because
     * a registration that vanishes between the two would strand its hold.
     * [revalidate] deliberately does not - it takes a snapshot and works
     * outside the lock, where a registration arriving late has already checked
     * its own grant and one leaving late gets a harmless second revoke.
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
