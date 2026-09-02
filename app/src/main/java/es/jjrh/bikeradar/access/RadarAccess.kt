// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import android.app.Activity

/**
 * Who may read the radar stream, and who may act on the hardware.
 *
 * This is the seam between the cross-app IPC surface and the rider's consent.
 * The bound service asks; nothing here knows what binder is.
 *
 * A UID rather than a package, because [android.os.Binder.getCallingUid] is the
 * only caller identity a service can trust. Resolving it to a package, and
 * checking that package still carries the signing certificate the rider
 * approved, is this layer's job.
 */
interface RadarAccessGate {

    /** The target stream, battery level and connection state. */
    fun canRead(uid: Int): Boolean

    /** Tail-light mode and overlay visibility. Never implied by [canRead]. */
    fun canControl(uid: Int): Boolean
}

/**
 * The gate until a rider can grant anything.
 *
 * There is no consent screen and no grant store yet, so there is no answer this
 * could give but no. It denies by construction rather than behind a flag,
 * because a placeholder answering `true` would ship an ungated radar stream and
 * read as finished work.
 *
 * `RadarAccessGateStubTest` pins that it refuses, so replacing it is a
 * deliberate act rather than an edit that quietly opens the surface.
 */
object DeniedAccessGate : RadarAccessGate {
    override fun canRead(uid: Int): Boolean = false

    override fun canControl(uid: Int): Boolean = false
}

/**
 * Who is reading the stream right now, so the rider can see it.
 *
 * The bound service is the only thing that knows when a listener registers or
 * dies, and the foreground notification is the only place a rider looks during
 * a ride. This carries the first to the second. Reporting an empty set means
 * nothing is attached.
 *
 * Deliberately a set of package names rather than a count: naming the app that
 * is reading is actionable, "1 app is reading your radar" is not.
 */
interface RadarSharingReporter {

    fun onActiveConsumersChanged(packageNames: Set<String>)
}

/**
 * The reporter until the notification work lands. Drops what it is told.
 *
 * Discarding is safe in a way that a permissive [DeniedAccessGate] would not
 * be: the cost is that the rider is not yet shown something, never that
 * something is allowed which should not be.
 */
object NoSharingReporter : RadarSharingReporter {
    override fun onActiveConsumersChanged(packageNames: Set<String>) = Unit
}

/**
 * The consent screen's contract, for a consumer app to build against.
 *
 * The consumer starts this from its own foreground with
 * `startActivityForResult` at the moment its user asks to connect. Bike Radar
 * never launches it: a consent screen thrown over a moving map is the failure
 * this shape avoids.
 *
 * Launching grants nothing. The rider's answer is the grant, and calling again
 * when a grant exists shows its current state, so one screen covers connecting
 * and changing your mind.
 */
object RadarConsent {

    /** Explicit component is safer, but the action is what a consumer matches on. */
    const val ACTION = "es.jjrh.bikeradar.action.REQUEST_RADAR_ACCESS"

    /** Booleans on a RESULT_OK intent. Read them; either may be false. */
    const val EXTRA_READ = "es.jjrh.bikeradar.extra.READ"
    const val EXTRA_CONTROL = "es.jjrh.bikeradar.extra.CONTROL"

    /** A ride is in progress. Retryable once it ends. */
    const val RESULT_RIDE_IN_PROGRESS = Activity.RESULT_FIRST_USER

    /** No calling package, or a shared UID. Not retryable. */
    const val RESULT_CALLER_UNKNOWN = Activity.RESULT_FIRST_USER + 1

    /**
     * The rider answered, but the answer could not be saved. Do not treat this
     * as a grant: nothing was stored and every later call will refuse.
     */
    const val RESULT_NOT_STORED = Activity.RESULT_FIRST_USER + 2
}
