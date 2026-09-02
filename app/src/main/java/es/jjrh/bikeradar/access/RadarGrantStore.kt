// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One app's standing permission, as the rider granted it.
 *
 * [certDigest] pins the grant to the app that was approved: a package name
 * survives an uninstall and a reinstall from another source, a signing key does
 * not. [lastUsedAtMs] is approximate in two directions and is meant to be:
 * it is stamped whenever the gate answers yes, which includes a revalidation
 * triggered by an unrelated app, and the write is throttled to a minute. It
 * answers "has this app been near the radar lately", never "exactly when".
 * It exists so the settings list can show what has gone quiet,
 * since grants do not expire on their own.
 */
data class RadarGrant(
    val packageName: String,
    val certDigest: String,
    val label: String,
    val grantedAtMs: Long,
    val lastUsedAtMs: Long,
    val read: Boolean,
    val control: Boolean,
)

/**
 * The rider's standing grants, keyed by package.
 *
 * An interface because the gate's rules are worth testing without Android.
 */
interface RadarGrantStore {

    fun grantFor(packageName: String): RadarGrant?

    fun all(): List<RadarGrant>

    /** False when the store could not be read, so nothing was written. */
    fun put(grant: RadarGrant): Boolean

    /** False when the store could not be read, so nothing was removed. */
    fun revoke(packageName: String): Boolean

    fun markUsed(packageName: String, atMs: Long)
}

/**
 * Grants in SharedPreferences, as one JSON array under a single key.
 *
 * SharedPreferences rather than a file because internal storage is already in
 * the Android backup and the device transfer, so a rider's approvals follow
 * them to a new phone. The Privacy screen says so; they are not device-local.
 *
 * Every method holds one monitor across its read and its write. The gate
 * answers binder calls on a pool thread while the settings screen writes on the
 * main one, so an unsynchronised read-modify-write lets a stale copy resurrect
 * a grant the rider has just revoked.
 *
 * The monitor is shared by every instance rather than held per object, because
 * two instances over the same file are two writers and a per-object lock would
 * serialise neither against the other.
 *
 * **Granting and revoking are deliberately not journalled.** Nothing here
 * writes to the capture log, the link journal or a crash report. The names in
 * this file are the rider's installed third-party apps, and a capture is a file
 * riders attach to hardware reports, so recording consent events would put a
 * list of what they have installed into an artefact meant to be shared. The
 * store itself is the record: it carries `grantedAtMs` and `lastUsedAtMs`, and
 * the Settings screen reads both.
 */
class PrefsRadarGrantStore(private val prefs: SharedPreferences) : RadarGrantStore {

    override fun grantFor(packageName: String): RadarGrant? = all().firstOrNull { it.packageName == packageName }

    override fun all(): List<RadarGrant> = synchronized(LOCK) { read() ?: emptyList() }

    override fun put(grant: RadarGrant): Boolean {
        synchronized(LOCK) {
            val current = read() ?: return false
            write(current.filterNot { it.packageName == grant.packageName } + grant, durable = true)
            _writes.value += 1
            return true
        }
    }

    override fun revoke(packageName: String): Boolean {
        synchronized(LOCK) {
            val current = read() ?: return false
            // Durable, because a revoke lost to process death before the flush
            // is the one write here whose loss is a security event.
            write(current.filterNot { it.packageName == packageName }, durable = true)
            _writes.value += 1
            return true
        }
    }

    override fun markUsed(packageName: String, atMs: Long) {
        synchronized(LOCK) {
            val current = read() ?: return
            val existing = current.firstOrNull { it.packageName == packageName } ?: return
            // The settings list shows how long ago an app last read, so a finer
            // stamp changes nothing on screen. Without the floor this is a
            // whole-file rewrite on every allowed call, which is a radar frame.
            if (atMs - existing.lastUsedAtMs < USE_STAMP_RESOLUTION_MS) return
            write(
                current.map { if (it.packageName == packageName) it.copy(lastUsedAtMs = atMs) else it },
                durable = false,
            )
        }
    }

    /**
     * Null when the stored value is present but unreadable, as distinct from
     * absent. A write must not proceed from that: every method here rewrites
     * the whole array, so writing what could be read would silently discard
     * every grant that could not be.
     */
    private fun read(): List<RadarGrant>? {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RadarGrant(
                    packageName = o.getString("pkg"),
                    certDigest = o.getString("cert"),
                    label = o.optString("label", o.getString("pkg")),
                    grantedAtMs = o.optLong("granted", 0L),
                    lastUsedAtMs = o.optLong("used", 0L),
                    read = o.optBoolean("read", false),
                    control = o.optBoolean("control", false),
                )
            }
        }.getOrNull()
    }

    private fun write(grants: List<RadarGrant>, durable: Boolean) {
        val arr = JSONArray()
        for (g in grants) {
            arr.put(
                JSONObject()
                    .put("pkg", g.packageName)
                    .put("cert", g.certDigest)
                    .put("label", g.label)
                    .put("granted", g.grantedAtMs)
                    .put("used", g.lastUsedAtMs)
                    .put("read", g.read)
                    .put("control", g.control),
            )
        }
        val editor = prefs.edit().putString(KEY, arr.toString())
        if (durable) editor.commit() else editor.apply()
    }

    companion object {
        /** Its own file, like the other stores here, so a clear of one is not a clear of all. */
        const val PREFS_NAME = "radar-access"

        private val _writes = MutableStateFlow(0L)

        /**
         * Bumped on every write that CHANGES A GRANT, so a live consumer's held
         * decision is invalidated the moment the rider changes their mind.
         *
         * The bound service checks a grant once per registration rather than
         * per frame, because resolving a package through the PackageManager at
         * radar cadence is not free. That trade is only honest while something
         * revokes the held answer, and this is that something. A counter
         * rather than the grants themselves: every reader re-reads the store,
         * so shipping the contents here would be a second copy to keep in step.
         *
         * [markUsed] writes and deliberately does NOT bump, and that exclusion
         * is load-bearing rather than an oversight: revalidation consults the
         * gate, the gate stamps, and a stamp that bumped would schedule the
         * next revalidation, leaving the service revalidating every consumer
         * once a minute for the length of a ride. A use stamp cannot change who
         * is allowed what, so there is nothing to invalidate.
         * `markUsedDoesNotTriggerRevalidation` pins it.
         */
        val writes: StateFlow<Long> = _writes

        private val LOCK = Any()
        private const val KEY = "radar_access_grants"
        private const val USE_STAMP_RESOLUTION_MS = 60_000L
    }
}
