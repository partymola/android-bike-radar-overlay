// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.HaClient
import es.jjrh.bikeradar.HaConnectionTest
import es.jjrh.bikeradar.HaUrlPolicy
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.HaIntent
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Why [url] may not be saved, or null when it may. Both Home Assistant
 * screens check this before any save, since every publish refuses the same
 * URLs (`HaStepConnectionTest`, `SettingsHaConnectionTest`).
 */
internal fun urlRefusalMessage(url: String): String? = when (val r = HaUrlPolicy.validate(url)) {
    is HaUrlPolicy.Result.CleartextWanRefused -> HaUrlPolicy.refusalMessage(r.host)
    HaUrlPolicy.Result.Malformed -> HaUrlPolicy.malformedMessage()
    HaUrlPolicy.Result.Empty, HaUrlPolicy.Result.Ok -> null
}

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
    }
    return result
}
