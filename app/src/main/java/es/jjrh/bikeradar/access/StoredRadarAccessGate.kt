// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

/**
 * The real gate: a caller may do what the rider granted its package, and only
 * while it can still prove it is the app the rider approved.
 *
 * Every answer is recomputed from the store and the PackageManager. Nothing is
 * cached, because the cheap wrong version of this caches a decision and keeps
 * honouring it after the rider revokes.
 */
class StoredRadarAccessGate(
    private val store: RadarGrantStore,
    private val identity: PackageIdentity,
    private val now: () -> Long,
) : RadarAccessGate {

    override fun canRead(uid: Int): Boolean = allows(uid) { it.read }

    override fun canControl(uid: Int): Boolean = allows(uid) { it.control }

    private fun allows(uid: Int, wanted: (RadarGrant) -> Boolean): Boolean {
        val caller = identity.resolve(uid) ?: return false
        val grant = store.grantFor(caller.packageName) ?: return false
        // Refuse, and leave the grant alone. A signing key the app cannot prove
        // it owns is either a different app wearing the name or a rotation this
        // check cannot see; destroying the grant would make the rider re-consent
        // with no way to tell which it was.
        if (grant.certDigest !in identity.digests(caller.packageName)) return false
        if (!wanted(grant)) return false
        store.markUsed(caller.packageName, now())
        return true
    }
}
