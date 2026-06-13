// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * The single home of every device-name heuristic. The same vocabulary
 * used to be duplicated across the service, the scan receiver, and four
 * UI screens, each with a slightly different word list - so a device
 * the service would happily link could simultaneously read as "Not
 * paired" on the home screen.
 *
 * The predicates deliberately differ in width because their consumers
 * carry different risk:
 *
 *  - [isRearAdvert] gates the LIVE radar link. Conservative on purpose:
 *    linking the wrong device streams a confidently-wrong "all clear",
 *    so only names that are unambiguously a rear unit pass. A radar the
 *    advert matcher misses can still be pinned explicitly
 *    ([es.jjrh.bikeradar.data.Prefs.radarMac]); the pin overrides this
 *    matcher in the link path.
 *  - [isRadarName] is the bonded-device superset: enumeration, the pin
 *    picker, and every UI "is a radar paired?" affordance. Wider is
 *    safe here - the worst case is a stray green dot, not a wrong link.
 *  - [isUnambiguousRadar] excludes rear units from the DASHCAM picker
 *    without excluding the front cameras that share the same vendor
 *    word ("Varia Vue" must stay offerable as a dashcam).
 *  - [isKnownAccessory] is the widest: which advert sightings wake the
 *    battery-read path at all (radar or dashcam). A pinned radar MAC
 *    bypasses it (see [BatteryScanReceiver]).
 *
 * Vendor words appear here as device-name-matching literals only (the
 * hardware advertises them - e.g. the radar's local name is literally
 * "RearVue8"); they stay out of class/file/API names per the repo
 * naming rules.
 */
object DeviceNameMatcher {

    /** Live-advert link gate for the rear radar (see class KDoc). */
    fun isRearAdvert(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("rear") || n.contains("rtl")
    }

    /** Bonded-device radar heuristic: enumeration, pin picker, UI
     *  paired/battery checks. Superset of [isRearAdvert]. */
    fun isRadarName(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return n.contains("rear") || n.contains("rtl") || n.contains("varia")
    }

    /** True when the name can ONLY be a rear radar - used to keep rear
     *  units out of the dashcam picker while leaving "...Vue..." front
     *  cameras offerable. */
    fun isUnambiguousRadar(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("rearvue") ||
            n.contains("rtl") ||
            (n.contains("varia") && !n.contains("vue"))
    }

    /** Any device class the battery-scan path cares about (rear radar
     *  or front camera/light), by advertised name. */
    fun isKnownAccessory(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("varia") ||
            n.contains("vue") ||
            n.contains("rearvue") ||
            n.contains("rtl") ||
            n.contains("garmin")
    }
}
