// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.access

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.ipc.RadarContract.Consent
import es.jjrh.bikeradar.radarStreamIsLive
import es.jjrh.bikeradar.ui.SafetyNoticeScreen
import es.jjrh.bikeradar.ui.UiTheme

/**
 * Asks the rider whether another app may use the radar.
 *
 * Started by that app with `startActivityForResult`, never by this one: a
 * consent screen thrown over a moving map is the failure the shape avoids, and
 * `getCallingPackage` is the only caller identity an activity can trust.
 *
 * The rules live in [RadarConsentDecider]. What is here is the screen and the
 * result, so the decisions can be tested without one.
 */
class RadarConsentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        val decider = RadarConsentDecider(
            store = PrefsRadarGrantStore(
                getSharedPreferences(PrefsRadarGrantStore.PREFS_NAME, MODE_PRIVATE),
            ),
            identity = SystemPackageIdentity(packageManager),
            rideInProgress = { radarStreamIsLive(RadarStateBus.state.value, System.currentTimeMillis()) },
            now = { System.currentTimeMillis() },
        )

        when (val request = decider.open(callingPackage)) {
            // No screen. Mid-ride the rider is looking at the road, and an
            // unidentifiable caller has nobody to explain anything to. The
            // asking app has the result code and its own place to say why.
            is ConsentRequest.Refuse ->
                finishWith(request.resultCode, read = false, control = false)

            // The riding-aid notice gates every activity this app can put in
            // front of a rider, and this one is exported, so the notice comes
            // first here too. After the refusals above rather than before
            // them, so a rider mid-ride is still shown no screen by this
            // activity (NoScreenBeforeTheNoticeTest).
            is ConsentRequest.Ask -> setContent {
                var acknowledged by remember { mutableStateOf(prefs.safetyNoticeAcknowledged) }
                if (!acknowledged) {
                    SafetyNoticeScreen(
                        onAcknowledge = {
                            prefs.safetyNoticeAcknowledged = true
                            acknowledged = true
                        },
                    )
                } else {
                    UiTheme {
                        RadarConsentAsk(
                            request = request,
                            onCancel = { finishWith(RESULT_CANCELED, read = false, control = false) },
                            onSave = { read, control ->
                                val code = decider.decide(request.packageName, request.label, read, control)
                                finishWith(code, read, control)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun finishWith(resultCode: Int, read: Boolean, control: Boolean) {
        setResult(
            resultCode,
            Intent()
                .putExtra(Consent.EXTRA_READ, read)
                .putExtra(Consent.EXTRA_CONTROL, control),
        )
        finish()
    }
}
