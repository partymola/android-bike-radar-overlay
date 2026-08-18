// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import es.jjrh.bikeradar.RideLocationResolver
import es.jjrh.bikeradar.data.Prefs

/**
 * The rider's manual coordinates, for the three screens that let them set some.
 *
 * One save and one clear serve all three, so a transposed pair or a clear that
 * never reaches [Prefs] is a single defect that a test on any one screen
 * catches (`SettingsPermissionsCoordinatesTest`, `SettingsLightsCoordinatesTest`,
 * `OnboardingPermissionsStepCoordinatesTest`).
 *
 * The STATE is still per screen: an entry seeds from [Prefs], and restores the
 * saved value thereafter, so two screens alive at once do not track each other
 * live. [Prefs] is the point they agree at, and every mutator writes it in the
 * same call.
 */
internal class ManualLocationState(
    private val prefs: Prefs,
    private val latState: MutableState<Double?>,
    private val lonState: MutableState<Double?>,
    private val dialogState: MutableState<Boolean>,
) {
    // The states are the caller's, so a restore has one copy to update.
    val lat: Double? get() = latState.value
    val lon: Double? get() = lonState.value
    val dialogVisible: Boolean get() = dialogState.value

    /** Rendered form for the card's value row, or null when unset or invalid. */
    val summary: String? get() = RideLocationResolver.summary(lat, lon)

    fun openDialog() {
        dialogState.value = true
    }

    fun dismissDialog() {
        dialogState.value = false
    }

    fun save(newLat: Double, newLon: Double) {
        latState.value = newLat
        lonState.value = newLon
        prefs.setManualLocation(newLat, newLon)
        dialogState.value = false
    }

    fun clear() {
        latState.value = null
        lonState.value = null
        prefs.setManualLocation(null, null)
    }
}

/** Seeds from [Prefs] on first entry; restores the saved value thereafter. */
@Composable
internal fun rememberManualLocation(prefs: Prefs): ManualLocationState {
    val lat = rememberSaveable { mutableStateOf(prefs.manualLocationLat) }
    val lon = rememberSaveable { mutableStateOf(prefs.manualLocationLon) }
    val dialogVisible = rememberSaveable { mutableStateOf(false) }
    return remember(prefs, lat, lon, dialogVisible) {
        ManualLocationState(prefs, lat, lon, dialogVisible)
    }
}

/** The coordinate dialog, shown only while [state] says so. */
@Composable
internal fun ManualLocationDialog(state: ManualLocationState) {
    if (!state.dialogVisible) return
    CoordinateEntryDialog(
        initialLat = state.lat,
        initialLon = state.lon,
        onSave = state::save,
        onDismiss = state::dismissDialog,
    )
}

/**
 * The location card's alternative, wired to [state]. Null for every spec but
 * the location one - see [locationAlternative]. This is what a STATEFUL screen
 * calls; the stateless leaves take loose lambdas and call [locationAlternative]
 * directly.
 */
internal fun locationAlternativeFor(
    spec: PermissionSpec,
    state: ManualLocationState,
): PermissionAlternative? = locationAlternative(
    spec = spec,
    manualLocationSummary = state.summary,
    onEnterCoordinates = state::openDialog,
    onClearCoordinates = state::clear,
)
