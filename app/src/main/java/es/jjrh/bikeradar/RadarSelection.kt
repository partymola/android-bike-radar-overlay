// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context

/**
 * Radar device-selection policy (hybrid). The app finds the rear radar by
 * NAME-MATCH by default - zero config for the common rider with a single
 * radar. A rider who keeps more than one radar bonded (a spare, or a second
 * bike) can pin THIS bike's radar explicitly; [es.jjrh.bikeradar.data.Prefs.radarMac]
 * then overrides the name-match so the app never silently streams from the
 * wrong rear-facing safety device (a confidently-wrong "all clear" from a
 * radar that isn't on this bike is the failure this guards against).
 *
 * The override is a SOFT preference: if the chosen MAC is no longer bonded
 * (e.g. a re-pair changed the address), selection falls back to name-match
 * rather than stranding the rider with no radar.
 *
 * The decision function [shouldLinkRadar] is pure so the fallback matrix is
 * unit-tested without a Bluetooth stack; [bondedRadars] is the thin Android
 * enumeration around it.
 */
object RadarSelection {

    /** True if [name] looks like a rear radar by its advertised/bonded local
     *  name. Used for bonded-device enumeration and the picker. Deliberately a
     *  SUPERSET of the live advert matcher ([DeviceNameMatcher.isRearAdvert]):
     *  the pin overrides the advert matcher anyway (a chosen MAC links
     *  regardless of [shouldLinkRadar]'s `nameMatchesRadar`), so the wider
     *  bonded matcher cannot strand a pinned radar. */
    fun isRadarName(name: String?): Boolean = DeviceNameMatcher.isRadarName(name)

    /**
     * Whether the scan sighting `(mac, nameMatchesRadar)` is THE radar to link.
     *
     * - A [chosenMac] that is still present in [bondedRadarMacs] WINS: only that
     *   exact MAC links; other name-matching radars are ignored. This is the
     *   anti-wrong-radar guard for the multi-radar-bonded case.
     * - Otherwise (no choice stored, or the stored choice is no longer bonded)
     *   fall back to name-match - identical to the pre-override behaviour.
     *
     * [nameMatchesRadar] is supplied by the caller so the live advert path can
     * keep using its own matcher and the fallback stays exactly the legacy
     * behaviour.
     */
    fun shouldLinkRadar(
        mac: String,
        nameMatchesRadar: Boolean,
        chosenMac: String?,
        bondedRadarMacs: Set<String>,
    ): Boolean {
        if (chosenMac != null && bondedRadarMacs.any { it.equals(chosenMac, ignoreCase = true) }) {
            return mac.equals(chosenMac, ignoreCase = true)
        }
        return nameMatchesRadar
    }

    /** More than one bonded radar means the name-match is ambiguous and the UI
     *  should prompt the rider to pick which one is on this bike. */
    fun isAmbiguous(bondedRadarMacs: Set<String>): Boolean = bondedRadarMacs.size > 1

    /**
     * Is there a radar for this bike to talk to at all?
     *
     * This is the ONE definition of "the rider has a radar", and every surface
     * that reports link state must use it, because it is the question
     * [shouldLinkRadar] answers when it decides whether to stream. Two ways a
     * narrower test gets it wrong, and both report "Not paired" about a radar
     * that is delivering targets:
     *
     *  - A name-match over the bonded list misses the "my radar isn't listed"
     *    escape hatch, where the pinned device's name never matches.
     *  - Requiring a single RESOLVED radar misses the ambiguous case: with two
     *    bonded and none pinned, [shouldLinkRadar] falls back to name-match and
     *    links one, so the rider has cover even though nothing says which unit
     *    it came from.
     *
     * This IS adapter-dependent, unavoidably: [bondedDevices] reads
     * `getBondedDevices`, which returns an empty set while the adapter is off,
     * so with the radio down the app cannot tell a bondless phone from a
     * bonded one and this returns false either way. Callers that also gate on
     * the radio are agreeing with that rather than adding to it. Do not
     * describe this as radio-independent.
     */
    fun hasLinkableRadar(allBonded: List<BondedRadar>, chosenMac: String?): Boolean = allBonded.any { it.mac.equals(chosenMac, ignoreCase = true) } ||
        allBonded.any { isRadarName(it.name) }

    /**
     * WHICH radar this bike is pinned to, by name, or null when that is not
     * settled - none bonded, or several bonded with none pinned.
     *
     * Only for naming the device on its own screen, never for deciding whether
     * the rider has cover: null here means "cannot say which", not "there is
     * none", and the ambiguous case still streams. [hasLinkableRadar] is the
     * question every status surface asks.
     */
    fun activeRadarName(allBonded: List<BondedRadar>, chosenMac: String?): String? {
        // The pin resolves against ALL bonded devices, not the name-matched
        // subset, which is what makes the escape hatch work.
        val pinned = allBonded.firstOrNull { it.mac.equals(chosenMac, ignoreCase = true) }
        if (pinned != null) return pinned.name
        val named = allBonded.filter { isRadarName(it.name) }
        return if (named.size == 1) named.first().name else null
    }

    /** A bonded radar candidate: its address + readable local name. */
    data class BondedRadar(val mac: String, val name: String)

    /** Bonded devices whose name matches the radar heuristic. Empty on any
     *  Bluetooth permission / adapter error (the caller then falls back to
     *  name-match, preserving today's behaviour). */
    fun bondedRadars(ctx: Context): List<BondedRadar> = bondedDevices(ctx).filter { isRadarName(it.name) }

    /** ALL bonded devices, regardless of name. Backs the "my radar isn't
     *  listed" escape hatch: a radar the name heuristic doesn't know can
     *  still be pinned, and a pinned MAC must count as "still bonded" in
     *  [shouldLinkRadar] even though its name never matched. Empty on any
     *  Bluetooth permission / adapter error. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(ctx: Context): List<BondedRadar> = try {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter?.bondedDevices.orEmpty().map { dev ->
            val n = try {
                dev.name
            } catch (_: Throwable) {
                null
            }
            BondedRadar(dev.address, n ?: dev.address)
        }
    } catch (_: Throwable) {
        emptyList()
    }
}
