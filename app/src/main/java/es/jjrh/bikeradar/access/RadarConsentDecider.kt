// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import android.app.Activity
import es.jjrh.bikeradar.ipc.RadarContract.Consent

/** What the consent screen should do about the app that opened it. */
sealed interface ConsentRequest {

    /** Put the question to the rider. [current] is null the first time. */
    data class Ask(
        val packageName: String,
        val label: String,
        val current: RadarGrant?,
    ) : ConsentRequest

    /** Answer the caller with [resultCode] and show nothing. */
    data class Refuse(val resultCode: Int) : ConsentRequest
}

/** What the consent screen's confirm button does with the toggles as they stand. */
enum class ConsentPrimaryAction {
    /** Store what the toggles say. */
    ALLOW,

    /** Both off over an existing grant: saving removes it. */
    REVOKE,

    /** Both off with nothing granted. There is no answer to store. */
    NOTHING,
}

/**
 * Both toggles off is not a grant, so a button reading "Allow" would store
 * nothing and say it had allowed something. Over an existing grant the same
 * answer is a revoke, and that is worth keeping reachable: a rider who wants
 * an app to stop is in the OTHER app, not in these Settings.
 */
fun consentPrimaryAction(hasGrant: Boolean, read: Boolean, control: Boolean): ConsentPrimaryAction = when {
    read || control -> ConsentPrimaryAction.ALLOW
    hasGrant -> ConsentPrimaryAction.REVOKE
    else -> ConsentPrimaryAction.NOTHING
}

/**
 * The consent screen's rules, without the screen.
 *
 * Kept apart from the activity because these are the decisions worth testing:
 * who may be asked about, when asking is refused, and what a rider's answer
 * does to the store.
 */
class RadarConsentDecider(
    private val store: RadarGrantStore,
    private val identity: PackageIdentity,
    private val rideInProgress: () -> Boolean,
    private val now: () -> Long,
) {

    /**
     * [callingPackage] is `Activity.getCallingPackage()`, which is null unless
     * the consumer started this for a result. That is the only caller identity
     * an activity can trust, so without it there is nobody to grant anything to.
     */
    fun open(callingPackage: String?): ConsentRequest {
        val packageName = callingPackage
            ?: return ConsentRequest.Refuse(Consent.RESULT_CALLER_UNKNOWN)

        // The gate refuses a shared UID, so a grant made here could never be
        // used. Refusing now says so, instead of leaving a dead grant behind.
        val uid = identity.uidOf(packageName)
            ?: return ConsentRequest.Refuse(Consent.RESULT_CALLER_UNKNOWN)
        val caller = identity.resolve(uid)
        if (caller?.packageName != packageName) {
            return ConsentRequest.Refuse(Consent.RESULT_CALLER_UNKNOWN)
        }
        if (identity.digests(packageName).isEmpty()) {
            return ConsentRequest.Refuse(Consent.RESULT_CALLER_UNKNOWN)
        }

        // A rider mid-ride is looking at the road, not at a permission screen.
        if (rideInProgress()) return ConsentRequest.Refuse(Consent.RESULT_RIDE_IN_PROGRESS)

        return ConsentRequest.Ask(packageName, caller.label, store.grantFor(packageName))
    }

    /**
     * Record what the rider decided. Answering no to both removes the grant
     * rather than storing an empty one, so a revoke through this screen is the
     * same state as never having granted.
     *
     * Returns the result code for the caller. [Activity.RESULT_OK] either way,
     * since the rider answered; the extras carry what they said.
     */
    fun decide(packageName: String, label: String, read: Boolean, control: Boolean): Int {
        // A write that did not land must not come back as OK. A store too
        // damaged to read refuses every write, and a consumer told OK would
        // believe it had standing access while every later call denied.
        if (!read && !control) {
            return if (store.revoke(packageName)) Activity.RESULT_OK else Consent.RESULT_NOT_STORED
        }
        // Any of the app's keys will do, because the gate checks membership in
        // the set rather than equality with one. Taking the lowest just makes
        // the stored value the same across two grants of the same app.
        val digest = identity.digests(packageName).minOrNull()
            ?: return Consent.RESULT_CALLER_UNKNOWN
        val stored = store.put(
            RadarGrant(
                packageName = packageName,
                certDigest = digest,
                label = label,
                grantedAtMs = now(),
                lastUsedAtMs = 0L,
                read = read,
                control = control,
            ),
        )
        return if (stored) Activity.RESULT_OK else Consent.RESULT_NOT_STORED
    }
}
