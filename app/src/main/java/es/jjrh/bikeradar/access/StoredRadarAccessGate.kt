// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
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
        // Unreadable signing info is evidence of nothing, so nothing is recorded.
        val digests = identity.digests(caller.packageName).ifEmpty { return false }
        // A key the app cannot prove may belong to another app using the name, to
        // the same app signed elsewhere, or to a rotation this check cannot see, so
        // the grant is kept rather than destroyed. The outcome is recorded because
        // Settings cannot see this app to ask about it later.
        val proven = grant.isOwnedBy(digests)
        if (grant.refused == proven) store.recordKeyCheck(caller.packageName, grant.certDigest, proven)
        if (!proven || !wanted(grant)) return false
        store.markUsed(caller.packageName, now())
        return true
    }
}
