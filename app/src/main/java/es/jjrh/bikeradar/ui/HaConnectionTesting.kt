// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import es.jjrh.bikeradar.HaClient
import es.jjrh.bikeradar.HaConnectionTest
import es.jjrh.bikeradar.HaFailure
import es.jjrh.bikeradar.HaFailureException
import es.jjrh.bikeradar.HaHealthBus
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.HaIntent
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tests [url] and [token] off the main thread, and saves them if Home
 * Assistant answers. Call from the main thread, where the fields are edited:
 * [stillTested] is read there with nothing between it and the caller's use of
 * the result, so a result whose fields were edited while it ran is dropped
 * (null) rather than shown or saved (`HaStepConnectionTest`,
 * `SettingsHaConnectionTest`).
 */
internal suspend fun runHaTest(
    url: String,
    token: String,
    creds: HaCredentials,
    prefs: Prefs,
    stillTested: () -> Boolean,
): HaConnectionTest? {
    val result = withContext(Dispatchers.IO) { HaClient(url, token).testConnection() }
    if (!stillTested()) return null
    if (result.ping.isSuccess) {
        creds.save(url, token)
        prefs.haLastValidatedEpochMs = System.currentTimeMillis()
        prefs.haIntent = HaIntent.YES
        // The MQTT check is a publish that worked, so an older failure no
        // longer describes these values (`SettingsHaConnectionTest`).
        if (result.mqtt?.isSuccess == true) HaHealthBus.reset()
    }
    return result
}

private val Throwable.haFailure get() = (this as? HaFailureException)?.failure ?: HaFailure.UNKNOWN

/** The HTTP status or the exception's class name: never the message, which is
 *  English and can carry the rider's host. */
private val Throwable.haDetail get() = (this as? HaFailureException)?.detail ?: javaClass.simpleName

/** What a chip says about [failure]; also the Settings card's last error. */
@StringRes
internal fun haFailureChip(failure: HaFailure): Int = when (failure) {
    HaFailure.URL_INVALID -> R.string.settings_ha_chip_url_invalid
    HaFailure.INSECURE_REFUSED -> R.string.settings_ha_chip_url_https
    HaFailure.HOST_UNREACHABLE -> R.string.settings_ha_chip_ha_unreachable
    HaFailure.TLS -> R.string.settings_ha_chip_ha_tls
    HaFailure.AUTH -> R.string.settings_ha_chip_ha_token
    HaFailure.NOT_HOME_ASSISTANT -> R.string.settings_ha_chip_ha_not_found
    HaFailure.SERVER_ERROR -> R.string.settings_ha_chip_ha_server_error
    HaFailure.MQTT_MISSING -> R.string.settings_ha_chip_mqtt_missing
    HaFailure.MQTT_AUTH -> R.string.settings_ha_chip_mqtt_token
    HaFailure.MQTT_FAILED -> R.string.settings_ha_chip_mqtt_failed
    HaFailure.UNKNOWN -> R.string.settings_ha_chip_ha_failed
}

/** What the rider can do about [failure]. Some take the failure's detail. */
@StringRes
private fun haFailureFix(failure: HaFailure): Int = when (failure) {
    HaFailure.URL_INVALID -> R.string.settings_ha_fix_url_invalid
    HaFailure.INSECURE_REFUSED -> R.string.settings_ha_fix_url_https
    HaFailure.HOST_UNREACHABLE -> R.string.settings_ha_fix_unreachable
    HaFailure.TLS -> R.string.settings_ha_fix_tls
    HaFailure.AUTH -> R.string.settings_ha_fix_token
    HaFailure.NOT_HOME_ASSISTANT -> R.string.settings_ha_fix_not_found
    HaFailure.SERVER_ERROR -> R.string.settings_ha_fix_server_error
    HaFailure.MQTT_MISSING -> R.string.settings_ha_fix_mqtt_missing
    HaFailure.MQTT_AUTH -> R.string.settings_ha_fix_mqtt_token
    HaFailure.MQTT_FAILED -> R.string.settings_ha_fix_mqtt_failed
    HaFailure.UNKNOWN -> R.string.settings_ha_fix_failed
}

/**
 * The chips a connection test leaves and, after a failure, one line in
 * [fixStyle] saying what to do. One polite live region, so TalkBack reads the
 * outcome wherever its focus is (`HaTestResultCopyTest`).
 */
@Composable
internal fun HaTestResult(
    pingResult: Result<String>?,
    mqttResult: Result<String>?,
    fixStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    if (pingResult == null && mqttResult == null) return
    val br = LocalBrColors.current
    val pingOk = stringResource(R.string.settings_ha_chip_ha_saved)
    val pingChip = pingResult?.let { r -> r.exceptionOrNull()?.let { stringResource(haFailureChip(it.haFailure)) } ?: pingOk }
    val mqttOk = stringResource(R.string.settings_ha_chip_mqtt_ready)
    val mqttChip = mqttResult?.let { r -> r.exceptionOrNull()?.let { stringResource(haFailureChip(it.haFailure)) } ?: mqttOk }
    val fix = (pingResult?.exceptionOrNull() ?: mqttResult?.exceptionOrNull())?.let {
        stringResource(haFailureFix(it.haFailure), it.haDetail)
    }
    val description = stringResource(
        R.string.settings_ha_result_cd,
        listOfNotNull(pingChip, mqttChip, fix).joinToString(". "),
    )
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
            contentDescription = description
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pingChip != null) BrChip(text = pingChip, color = if (pingResult.isSuccess) br.safe else br.danger)
            if (mqttChip != null) BrChip(text = mqttChip, color = if (mqttResult.isSuccess) br.safe else br.caution)
        }
        if (fix != null) Text(text = fix, style = fixStyle)
    }
}
