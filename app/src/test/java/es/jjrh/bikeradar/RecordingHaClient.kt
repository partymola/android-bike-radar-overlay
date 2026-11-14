// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import java.util.Collections

/**
 * An [HaClient] configured with an https URL that records every MQTT publish
 * instead of sending it, and answers with [answer]. A false answer sets
 * [failure] as the cause, as a real refused publish would.
 */
internal class RecordingHaClient : HaClient("https://ha.example.test", "tok") {
    /** Every publish attempted, as (topic, payload), recorded before it answers. */
    val sends: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Volatile var answer: suspend (topic: String, payload: String) -> Boolean = { _, _ -> true }

    @Volatile var failure: HaFailure = HaFailure.SERVER_ERROR

    override suspend fun publishMqtt(topic: String, payload: String, retain: Boolean): Boolean {
        sends += topic to payload
        val ok = answer(topic, payload)
        if (!ok) lastFailure = failure
        return ok
    }

    /** The battery states sent for [slug], in order. */
    fun batteryStates(slug: String): List<String> = sends.toList().filter { it.first == "bikeradar/$slug/battery" }.map { it.second }

    /** How many battery discoveries were sent for [slug]. */
    fun batteryDiscoveries(slug: String): Int = sends.toList().count { it.first == "homeassistant/sensor/bikeradar_${slug}_battery/config" }
}
