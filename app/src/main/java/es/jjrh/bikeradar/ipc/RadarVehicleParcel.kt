// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 JJ del Rio
// Permissive so another app can copy this contract into its own build.
// Licence text: LICENSES/Apache-2.0.txt. Consumers: additional-permission.txt
package es.jjrh.bikeradar.ipc

/**
 * One rear-radar target as carried over the cross-app contract.
 *
 * Not itself [android.os.Parcelable]: [RadarStateParcel] marshals these inline,
 * so one version number governs the whole layout. A nested parcelable would
 * need its own version, because its CREATOR cannot see the enclosing state's.
 *
 * [distanceM] is the distance BEHIND the rider unless [isAhead], in which case
 * it is the distance ahead. A rear radar mostly looks back, so an ahead reading
 * is the less reliable one.
 *
 * [lateralPos], [rangeXm], [closingKmh] and [size] are only measurements when
 * the enclosing state's capability bits say so. Otherwise they are defaults,
 * and a zero lateral offset means "not measured" rather than "dead centre".
 * [lateralKnown] already folds in the stream's lateral capability, so read it
 * alone: every flag on this wire is trustworthy on its own.
 *
 * [isAhead] has no capability bit because its default is the safe reading. A
 * range-only stream does not report the sign of the range, so every target
 * arrives as behind the rider, which is where a rear radar's targets nearly
 * always are. A consumer using it to exclude unreliable in-front tracks
 * excludes nothing there rather than excluding the wrong thing.
 */
data class RadarVehicleParcel(
    val id: Int,
    val distanceM: Int,
    val closingKmh: Int,
    val size: Int,
    val lateralPos: Float,
    val rangeXm: Float,
    val isAhead: Boolean,
    /** False when this frame's lateral read was not usable, whatever the source can do. */
    val lateralKnown: Boolean,
)
