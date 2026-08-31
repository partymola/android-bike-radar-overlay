// SPDX-License-Identifier: Apache-2.0 OR 0BSD
package es.jjrh.bikeradar.ipc;

import es.jjrh.bikeradar.ipc.IRadarTargetListener;

/**
 * Clean-room wire contract for sharing a rear radar between apps. Originated
 * in Crazy Capy Randonneur; dual-licensed Apache-2.0 OR 0BSD so any
 * integrating project can adopt it. Both apps must declare the same interface
 * for binder descriptors to match.
 */
interface IRadarService {
    // Tail-light modes, mirroring the radar's mode "type" bytes.
    const int LIGHT_NIGHT_FLASH = 0x14;
    const int LIGHT_DAY_FLASH = 0x13;
    const int LIGHT_SOLID = 0x11;
    const int LIGHT_PELOTON = 0x12;
    const int LIGHT_OFF = 0x1f;

    /** Rear-radar battery percent, or -1 when unknown / not connected. */
    int getBatteryPercent();

    /** True when a rear radar is currently connected. */
    boolean isConnected();

    /** Set the rear radar's tail-light mode (one of the LIGHT_* constants). */
    void setRadarLightMode(int mode);

    /** Show/hide the on-screen radar overlay. */
    void setOverlayVisible(boolean visible);

    /** Subscribe to ~5 Hz radar snapshots. Returns false if no radar is present. */
    boolean registerTargetListener(in IRadarTargetListener listener);

    /** Unsubscribe. Safe to call when not registered. */
    void unregisterTargetListener(in IRadarTargetListener listener);
}
