// SPDX-License-Identifier: Apache-2.0 OR 0BSD
package es.jjrh.bikeradar.ipc;

import es.jjrh.bikeradar.ipc.RadarStateParcel;

/**
 * Clean-room wire contract for sharing a rear radar between apps. Originated
 * in Crazy Capy Randonneur; dual-licensed Apache-2.0 OR 0BSD so any
 * integrating project can adopt it. Both apps must declare the same interface
 * for binder descriptors to match.
 *
 * Receives rear-radar snapshots pushed by the bound service.
 * Declared `oneway`: the push is fire-and-forget, never blocked on the client.
 */
oneway interface IRadarTargetListener {
    void onRadarState(in RadarStateParcel state);
}
