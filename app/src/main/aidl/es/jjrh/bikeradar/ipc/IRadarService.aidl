// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 JJ del Rio
// Permissive so another app can copy this contract into its own build.
// Licence text: LICENSES/Apache-2.0.txt. Consumers: additional-permission.txt
package es.jjrh.bikeradar.ipc;

import es.jjrh.bikeradar.ipc.IRadarListener;

/**
 * What another app on this phone can ask Bike Radar for.
 *
 * Binding grants nothing. Every method that returns radar data or acts on the
 * hardware checks the rider's standing grant for the calling app first, so a
 * bound consumer with no grant gets a refusal and an empty stream rather than
 * an error. Three methods are ungated by design, each because refusing it
 * would strand you: getContractVersion, unregisterTargetListener, and
 * setOverlayVisible(true).
 *
 * A refusal is the method's own no-answer value: false, or -1 from
 * getBatteryPercent. There is no way to ask whether you hold a grant, so treat
 * registerTargetListener returning false as that answer.
 *
 * Every method operates on the PRIMARY radar. Which unit that is comes from
 * the rider pinning one; with two radars bonded and no pin, the battery
 * answers whichever the app saw first. Multi-radar arrives later as new
 * methods, never by changing a signature here.
 */
interface IRadarService {

    /**
     * The wire version this app speaks, for negotiation before registering.
     * Ungated, so a consumer can decide whether it understands us before
     * asking the rider for anything.
     */
    int getContractVersion();

    /**
     * Start receiving snapshots. False when the rider has not granted read, or
     * when your listener's binder is already dead by the time we link to it.
     *
     * One live registration per package: a second call replaces the first
     * rather than being refused, so a consumer that reconnects after a crash
     * is not left without a stream it believes it has.
     */
    boolean registerTargetListener(IRadarListener listener);

    /**
     * Stop receiving, and give back the overlay if you were holding it. Safe to
     * call when not registered, and ungated, so you can still withdraw after
     * the rider has revoked you. It acts only on your own registration.
     */
    void unregisterTargetListener(IRadarListener listener);

    /** The primary radar's battery percentage, or -1 without a read grant or a reading. */
    int getBatteryPercent();

    /** Whether a radar is connected and delivering. False without a read grant. */
    boolean isConnected();

    /**
     * Set the radar's tail-light mode. Needs the control grant, which read
     * never implies. Returns false when refused or when no radar is linked.
     *
     * The int is one of RadarContract's LIGHT_MODE_ values, which are fixed
     * for a contract version; read getContractVersion first. Anything else is
     * refused rather than coerced to a mode.
     *
     * CALL THIS OFF YOUR MAIN THREAD. It is synchronous and reaches the radio:
     * it waits for the radar to acknowledge the write and gives up after a few
     * seconds, which is longer than Android's 5 s input-dispatch window. A call
     * made while handling a tap can therefore ANR your app.
     */
    boolean setRadarLightMode(int mode);

    /**
     * Hide or show Bike Radar's own on-screen overlay, for a consumer drawing
     * its own.
     *
     * HIDING needs the control grant and a live listener registration, so read
     * has to be granted too. That is a safety rule rather than a restriction:
     * the hold is anchored to the listener's binder, which is the only thing
     * here that dies when your process does. Without that anchor a crash would
     * leave the rider with no collision-warning display and nothing able to
     * restore it.
     *
     * SHOWING needs neither, so you can always give the display back, even
     * after the rider has revoked you.
     *
     * A hold is lifted when you unregister, when your listener's binder dies
     * (which covers your process being killed or uninstalled), when the rider
     * revokes or narrows the grant, when the last consumer unbinds, and when
     * Bike Radar's service stops. Every one of those is your process going away
     * or the rider intervening.
     *
     * SHOW IT YOURSELF WHEN YOU STOP DRAWING - in onStop, say. Nothing lifts
     * the hold for a consumer that is merely backgrounded, so a rider who
     * switches away from you keeps a screen with no radar on it. Bike Radar's
     * ride notification names a holder while anyone is holding, which is how
     * they find out; do not make them.
     *
     * The overlay is the DISPLAY only: this call reaches no audio path, by
     * design, because audio is the rider's primary warning channel.
     * `OverlayHideDoesNotReachTheAlertPathTest` pins that the alert call sits
     * outside the gate. It pins the shape of the code, not what any particular
     * rider hears - Bike Radar still stays quiet during a phone call, and still
     * ducks under other audio.
     */
    boolean setOverlayVisible(boolean visible);
}
