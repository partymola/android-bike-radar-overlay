// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 JJ del Rio
// Permissive so another app can copy this contract into its own build.
// Licence text: LICENSES/Apache-2.0.txt. Consumers: additional-permission.txt
package es.jjrh.bikeradar.ipc

import android.app.Activity

/**
 * The cross-app radar contract: what a consumer needs, and nothing else.
 *
 * Copy this, [RadarStateParcel], [RadarVehicleParcel] and the `.aidl`
 * definitions and you have a complete client under your own licence. It
 * references nothing in the app so that it can be copied at all;
 * `ContractIsSelfContainedTest` pins that.
 *
 * The capability bits separate a measurement from a default. A range-only radar
 * reports no closing speed, lateral offset, rider speed or vehicle class, and
 * the decoder fills those in, so without the bits a consumer draws a fabricated
 * lane position as though it were measured.
 */
object RadarContract {

    /** Bumped only when the wire layout changes. Written first on every parcel. */
    const val VERSION = 1

    /**
     * The app to bind to, as the release build installs. Side-by-side test
     * variants carry a suffix and are not what a consumer binds.
     */
    const val PACKAGE = "es.jjrh.bikeradar"

    /** The intent action the bound service answers to. */
    const val ACTION = "es.jjrh.bikeradar.action.RADAR_SERVICE"

    /**
     * The permission a consumer declares to bind at all. Install granted, so it
     * filters stray binds; the rider's per-app grant is what gates every answer.
     */
    const val PERMISSION = "es.jjrh.bikeradar.permission.RADAR"

    const val HAS_CLOSING_SPEED = 1
    const val HAS_LATERAL = 2
    const val HAS_RIDER_SPEED = 4
    const val HAS_VEHICLE_SIZE = 8

    const val RADAR_SIZE_CAR = 0
    const val RADAR_SIZE_TRUCK = 1

    /**
     * Reserved and never emitted. The radar reports cars and trucks only, so a
     * consumer that colours by class must not present a bike legend as
     * something this stream can produce.
     */
    const val RADAR_SIZE_BIKE = 2

    /**
     * Tail-light modes, as the ints `IRadarService.setRadarLightMode` takes.
     *
     * These values are the wire, not an enum's ordinals. They must not move
     * with the enum, and changing one is a [VERSION] bump.
     */
    const val LIGHT_MODE_NIGHT_FLASH = 0
    const val LIGHT_MODE_DAY_FLASH = 1
    const val LIGHT_MODE_SOLID = 2
    const val LIGHT_MODE_PELOTON = 3
    const val LIGHT_MODE_OFF = 4

    /**
     * Asking the rider for a grant. Binding gets you nothing until they answer,
     * so a client needs this as much as it needs the interface.
     *
     * The consumer starts this from its own foreground with
     * `startActivityForResult` when its user asks to connect. Bike Radar never
     * launches it: a consent screen thrown over a moving map is the failure
     * this shape avoids. Launching grants nothing, and calling again when a
     * grant exists shows its current state, so one screen covers connecting and
     * changing your mind.
     */
    object Consent {

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
         * The rider answered, but the answer could not be saved. Do not treat
         * this as a grant: nothing was stored and every later call refuses.
         */
        const val RESULT_NOT_STORED = Activity.RESULT_FIRST_USER + 2
    }
}
