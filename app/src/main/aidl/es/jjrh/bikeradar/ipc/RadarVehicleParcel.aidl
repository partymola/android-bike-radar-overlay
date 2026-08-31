// SPDX-License-Identifier: Apache-2.0 OR 0BSD
package es.jjrh.bikeradar.ipc;

/**
 * Clean-room wire contract for sharing a rear radar between apps. Originated
 * in Crazy Capy Randonneur; dual-licensed Apache-2.0 OR 0BSD so any
 * integrating project can adopt it. Both apps must declare the same interface
 * for binder descriptors to match.
 *
 * One rear-radar target, as carried over the IPC wire. Field meanings match the
 * Varia-class rear-radar protocol. Wire layout is the cross-app contract.
 */
parcelable RadarVehicleParcel;
