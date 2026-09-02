// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 JJ del Rio
// Permissive so another app can copy this contract into its own build.
// Licence text: LICENSES/Apache-2.0.txt. Consumers: additional-permission.txt
package es.jjrh.bikeradar.ipc;

import es.jjrh.bikeradar.ipc.RadarStateParcel;

/**
 * A consumer's sink for radar snapshots.
 *
 * Oneway: a slow or wedged consumer must not stall the radar pipeline, which
 * is delivering at frame rate on the app's own scope. Delivery is best effort
 * and a dropped frame is not an error, because the next snapshot supersedes it
 * entirely - there is no accumulated state a consumer can miss.
 */
oneway interface IRadarListener {

    /**
     * The whole current picture, not a delta. `streamLive` false means nothing
     * is delivering, and `isClear` says nothing in that case.
     */
    void onRadarState(in RadarStateParcel state);
}
