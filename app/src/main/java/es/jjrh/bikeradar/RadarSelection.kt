// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import es.jjrh.bikeradar.data.Prefs

/**
 * Radar device-selection policy (hybrid). The app finds the rear radar by
 * NAME-MATCH by default - zero config for the common rider with a single
 * radar. A rider who keeps more than one radar bonded (a spare, or a second
 * bike) can pin THIS bike's radar explicitly; [es.jjrh.bikeradar.data.Prefs.radarMac]
 * then overrides the name-match so the app never silently streams from the
 * wrong rear-facing safety device (a confidently-wrong "all clear" from a
 * radar that isn't on this bike is the failure this guards against). A pin
 * changed while a link runs moves it, unless the bonded read fails
 * ([shouldReplaceLink], `BikeRadarServiceSmokeTest.aPinChangedWhileLinkedMovesTheLink`).
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
     *  name. Used for bonded-device enumeration and, minus front cameras, the
     *  radar chooser ([choosableRadars]). Deliberately a
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

    /**
     * Whether a link running to [linkedMac] must move to [pinMac]: only when a
     * pin is set, bonded in [bondedMacs] now, and is another device. A failed
     * bonded read is empty, so it never cuts a working link
     * (`an empty bonded read never moves a running link`). Pinned in
     * `RadarSelectionTest` and, on the live link, `RadarLinkControllerHarnessTest`.
     */
    fun shouldReplaceLink(linkedMac: String?, pinMac: String?, bondedMacs: Set<String>): Boolean = linkedMac != null &&
        pinMac != null &&
        !linkedMac.equals(pinMac, ignoreCase = true) &&
        bondedMacs.any { it.equals(pinMac, ignoreCase = true) }

    /** More than one bonded radar means the name-match is ambiguous and the UI
     *  should prompt the rider to pick which one is on this bike. */
    fun isAmbiguous(bondedRadarMacs: Set<String>): Boolean = bondedRadarMacs.size > 1

    /**
     * Is there a radar for this bike to talk to at all?
     *
     * This is the ONE definition of "the rider has a radar", and every surface
     * that reports link state must use it, because it is the question
     * [shouldLinkRadar] answers when it decides whether to stream. Setup's
     * PAIRED card is the one deliberate exception: it asks [setupHasRadar],
     * which leaves out an unpinned front camera. Two ways a narrower test gets
     * it wrong, and both report "Not paired" about a radar that is delivering
     * targets:
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
     * settled - none bonded, or several radars bonded with none pinned.
     *
     * Only for naming the device on its own screen, never for deciding whether
     * the rider has cover: null here means "cannot say which", not "there is
     * none", and the ambiguous case still streams. [hasLinkableRadar] is the
     * question the status surfaces ask.
     */
    fun activeRadarName(allBonded: List<BondedRadar>, chosenMac: String?): String? = activeRadar(allBonded, chosenMac)?.name

    /** The device [activeRadarName] names. */
    fun activeRadar(allBonded: List<BondedRadar>, chosenMac: String?): BondedRadar? {
        // The pin resolves against ALL bonded devices, not the name-matched
        // subset, which is what makes the escape hatch work.
        val pinned = allBonded.firstOrNull { it.mac.equals(chosenMac, ignoreCase = true) }
        if (pinned != null) return pinned
        // A front camera sharing the vendor word is never named as the radar
        // (`a front camera beside the radar does not unsettle it`,
        // `a front camera alone is not named as the radar`).
        return choosableRadars(allBonded).singleOrNull()
    }

    /** The radars a rider chooses between: every name [isRadarName] accepts
     *  except a front camera sharing the vendor word, which is never offered as
     *  the radar (`a front camera is never offered as the radar`). A name the
     *  live link accepts on "rear" alone is choosable too
     *  (`two radars named only by rear are offered the chooser`). */
    fun choosableRadars(allBonded: List<BondedRadar>): List<BondedRadar> = allBonded.filter { isRadarName(it.name) && !DeviceNameMatcher.isFrontCamera(it.name) }

    /**
     * Whether to list the [choosableRadars]: when more than one is bonded, or
     * when the pin is on another bonded device while one is, so a wrong pick
     * made through "my radar isn't listed" can be undone
     * (`a pin on another bonded device offers the one named radar back`).
     */
    fun shouldOfferChooser(allBonded: List<BondedRadar>, chosenMac: String?): Boolean = choosableRadars(allBonded).size > 1 || pinOffNamedRadars(allBonded, chosenMac) != null

    /**
     * The pinned device, when it is a bonded device other than the
     * [choosableRadars] while one of those is paired; else null
     * (`a pin off the named radars is the pinned device, while one is paired`).
     * [shouldOfferChooser] then always has the named radar to offer back
     * (`a pin off the named radars is always offered the chooser`).
     */
    fun pinOffNamedRadars(allBonded: List<BondedRadar>, chosenMac: String?): BondedRadar? {
        val pinned = allBonded.firstOrNull { it.mac.equals(chosenMac, ignoreCase = true) } ?: return null
        val named = choosableRadars(allBonded)
        return pinned.takeIf { named.isNotEmpty() && pinned !in named }
    }

    /** Every bonded device except the ones the app already knows are something
     *  else: [knownOtherMacs] (the dashcam, the eBike) and anything named as a
     *  front camera, saved as the dashcam or not
     *  (`the dashcam, the eBike and front cameras are never offered as the radar`). */
    fun pickableDevices(allBonded: List<BondedRadar>, knownOtherMacs: List<String?>): List<BondedRadar> = allBonded.filterNot { dev ->
        DeviceNameMatcher.isFrontCamera(dev.name) || knownOtherMacs.any { it.equals(dev.mac, ignoreCase = true) }
    }

    /** The devices behind Settings' "my radar isn't listed": the
     *  [pickableDevices] that are not [choosableRadars], which the chooser
     *  above them already lists. */
    fun unlistedDevices(allBonded: List<BondedRadar>, knownOtherMacs: List<String?>): List<BondedRadar> = pickableDevices(allBonded, knownOtherMacs) - choosableRadars(allBonded).toSet()

    /**
     * Whether setup offers its device pick: until the active radar's name can
     * only be a rear unit ([DeviceNameMatcher.isUnambiguousRadar]). So it stays
     * while none is settled, while the pin sits on another device, so a wrong
     * pick can be changed on the screen it was made on, and while the radar is
     * known only by a wider name such as "rear" (`OnboardingPairingRadarPickTest`).
     */
    fun setupOffersPick(allBonded: List<BondedRadar>, chosenMac: String?): Boolean {
        val active = activeRadar(allBonded, chosenMac) ?: return true
        return !DeviceNameMatcher.isUnambiguousRadar(active.name)
    }

    /**
     * Whether setup's radar card reads PAIRED: a pin still bonded, or a
     * [choosableRadars] name. Narrower than [hasLinkableRadar] by the front
     * cameras, which the live link never streams from either, so a rider with
     * only a front camera paired is sent to pair the radar rather than told
     * one is paired (`a front camera alone is not a paired radar`).
     * [hasLinkableRadar], which the dead-radar warnings read, still counts them.
     */
    fun setupHasRadar(allBonded: List<BondedRadar>, chosenMac: String?): Boolean = allBonded.any { it.mac.equals(chosenMac, ignoreCase = true) } ||
        choosableRadars(allBonded).isNotEmpty()

    /** Pin [radar] as this bike's. The only path that writes a pin. A
     *  different unit drops the stored firmware revision, which described the
     *  old one (`RadarSelectionPinTest`). One edit, because a running service
     *  moves its link on the change and must not start it under the old name
     *  (`RadarSelectionPinTest.pinning is one change`). */
    fun pin(prefs: Prefs, radar: BondedRadar) {
        prefs.setRadarPin(radar.mac, radar.name, keepFirmwareRev = radar.mac.equals(prefs.radarMac, ignoreCase = true))
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
