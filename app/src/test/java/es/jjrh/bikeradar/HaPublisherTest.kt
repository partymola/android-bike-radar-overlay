// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentHashMap

/**
 * The optional-HA contract (with HA unconfigured every publish path is a clean
 * no-op), the battery send's throttle and health reporting, and the
 * front-light mode send, against a [RecordingHaClient]. Robolectric for HaCredentials' SharedPreferences + the
 * in-memory cryptor. The wire format is HaClient's own tests'.
 *
 * The configured publisher runs on `backgroundScope`, whose work
 * `advanceUntilIdle` skips, so these tests pump it with `runCurrent`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HaPublisherTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        HaHealthBus.reset()
    }

    @After fun tearDown() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
        HaHealthBus.reset()
    }

    private fun publisher(
        creds: HaCredentials = HaCredentials(app),
        rideStats: () -> RideStatsAccumulator = { RideStatsAccumulator() },
    ) = HaPublisher(
        scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        creds = creds,
        rideStats = rideStats,
        currentRadarMac = { "AA:BB:CC:DD:EE:FF" },
        macToSlug = { ConcurrentHashMap<String, String>().apply { put("AA:BB:CC:DD:EE:FF", "radar") } },
        loadKnownDevices = { listOf("radar" to "AA:BB:CC:DD:EE:FF") },
        slug = { it.lowercase(java.util.Locale.ROOT) },
    )

    private fun configured(ha: RecordingHaClient, scope: CoroutineScope, wallClock: () -> Long = { 1_000_000L }) = HaPublisher(
        scope = scope,
        creds = HaCredentials(app),
        rideStats = { RideStatsAccumulator() },
        currentRadarMac = { null },
        macToSlug = { ConcurrentHashMap() },
        loadKnownDevices = { emptyList() },
        slug = { it.lowercase(java.util.Locale.ROOT) },
        newClient = { _, _ -> ha },
        wallClock = wallClock,
    )

    @Test fun publishBatteryReturnsTrueWhenHaNotConfigured() {
        // The deliberate no-op-success: an unconfigured HA must report success
        // so the caller arms its 5-min throttle instead of retrying every advert.
        val ok = runBlocking { publisher().sendBattery("Radar", 80) }
        assertTrue("unconfigured battery publish must report success", ok)
    }

    @Test fun rideSummaryShortCircuitsBeforeTouchingStatsWhenHaNotConfigured() {
        // The HA-configured gate runs first, so an unconfigured rider never even
        // reads the ride-stats accumulator.
        var statsRead = false
        runBlocking {
            publisher(
                rideStats = {
                    statsRead = true
                    RideStatsAccumulator()
                },
            ).publishRideSummaryIfChanged()
        }
        assertFalse("ride-stats must not be consulted when HA is unconfigured", statsRead)
    }

    // ── the battery send ─────────────────────────────────────────────────────

    @Test fun aChangedBatteryLevelIsSent() = runTest {
        val ha = RecordingHaClient()
        val p = configured(ha, backgroundScope)
        assertTrue(p.sendBattery("Radar", 80))
        assertTrue(p.sendBattery("Radar", 79))
        assertEquals(listOf("80", "79"), ha.batteryStates("radar"))
        assertEquals(1, ha.batteryDiscoveries("radar"))
    }

    @Test fun anUnchangedLevelIsSentAgainOnlyAtTheFiveMinuteHeartbeat() = runTest {
        val ha = RecordingHaClient()
        var now = 1_000_000L
        val p = configured(ha, backgroundScope) { now }
        assertTrue(p.sendBattery("Radar", 80))
        now = 1_299_999L
        assertTrue("nothing new is still a success", p.sendBattery("Radar", 80))
        assertEquals(listOf("80"), ha.batteryStates("radar"))
        now = 1_300_000L
        assertTrue(p.sendBattery("Radar", 80))
        assertEquals(listOf("80", "80"), ha.batteryStates("radar"))
    }

    @Test fun aFailedSendIsRetriedByTheNextReadingAndNotBeforeIt() = runTest {
        val ha = RecordingHaClient()
        ha.answer = { topic, _ -> !topic.endsWith("/battery") }
        val p = configured(ha, backgroundScope)
        assertFalse(p.sendBattery("Radar", 80))
        runCurrent()
        assertEquals("no send without a new reading", listOf("80"), ha.batteryStates("radar"))
        ha.answer = { _, _ -> true }
        assertTrue(p.sendBattery("Radar", 80))
        assertEquals("the failure was not recorded as sent", listOf("80", "80"), ha.batteryStates("radar"))
    }

    @Test fun aFailedSendReportsItsCauseAndASuccessReportsOk() = runTest {
        val ha = RecordingHaClient()
        ha.failure = HaFailure.AUTH
        ha.answer = { topic, _ -> !topic.endsWith("/battery") }
        val p = configured(ha, backgroundScope)
        assertFalse(p.sendBattery("Radar", 80))
        val health = HaHealthBus.families.value[HaFamily.BATTERY]
        assertTrue("a failed send must reach the bus, got $health", health is HaHealth.Error)
        assertEquals(HaFailure.AUTH, (health as HaHealth.Error).cause)
        ha.answer = { _, _ -> true }
        assertTrue(p.sendBattery("Radar", 80))
        assertEquals(HaHealth.Ok, HaHealthBus.families.value[HaFamily.BATTERY])
    }

    // ── one send in flight per device ────────────────────────────────────────

    /** Holds the state send of [payload] until [release] completes. */
    private fun hangState(ha: RecordingHaClient, payload: String, release: CompletableDeferred<Unit>) {
        ha.answer = { topic, sent ->
            if (topic == "bikeradar/radar/battery" && sent == payload) release.await()
            true
        }
    }

    @Test fun offersDuringAHungSendCollapseToTheNewest() = runTest {
        val ha = RecordingHaClient()
        val release = CompletableDeferred<Unit>()
        hangState(ha, "80", release)
        val p = configured(ha, backgroundScope)
        p.offerBattery("Radar", 80)
        runCurrent()
        p.offerBattery("Radar", 79)
        p.offerBattery("Radar", 78)
        runCurrent()
        assertEquals("one send at a time", listOf("80"), ha.batteryStates("radar"))
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("80", "78"), ha.batteryStates("radar"))
        assertEquals(1, ha.batteryDiscoveries("radar"))
    }

    @Test fun aSupersededReadingGetsTheResultOfTheSendThatReplacedIt() = runTest {
        val ha = RecordingHaClient()
        val release = CompletableDeferred<Unit>()
        ha.answer = { topic, sent ->
            if (topic == "bikeradar/radar/battery" && sent == "80") release.await()
            !(topic == "bikeradar/radar/battery" && sent == "78")
        }
        val p = configured(ha, backgroundScope)
        val first = async { p.sendBattery("Radar", 80) }
        runCurrent()
        val superseded = async { p.sendBattery("Radar", 79) }
        val newest = async { p.sendBattery("Radar", 78) }
        runCurrent()
        release.complete(Unit)
        assertTrue(first.await())
        assertFalse(superseded.await())
        assertFalse(newest.await())
        assertEquals(listOf("80", "78"), ha.batteryStates("radar"))
    }

    @Test fun aRepeatOfTheReadingInFlightIsNotSentAgain() = runTest {
        val ha = RecordingHaClient()
        val release = CompletableDeferred<Unit>()
        hangState(ha, "80", release)
        val p = configured(ha, backgroundScope)
        p.offerBattery("Radar", 80)
        runCurrent()
        p.offerBattery("Radar", 80)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("80"), ha.batteryStates("radar"))
    }

    @Test fun aSendThatThrowsDoesNotStopTheNext() = runTest {
        val ha = RecordingHaClient()
        var thrown = false
        ha.answer = { topic, _ ->
            if (topic == "bikeradar/radar/battery" && !thrown) {
                thrown = true
                throw IllegalStateException("thrown by the test")
            }
            true
        }
        val p = configured(ha, backgroundScope)
        assertFalse(p.sendBattery("Radar", 80))
        assertTrue(p.sendBattery("Radar", 79))
        assertEquals(listOf("80", "79"), ha.batteryStates("radar"))
    }

    // ── the front-light mode send ────────────────────────────────────────────

    @Test fun aFailedFrontModeDiscoveryIsSentAgainWithTheNextMode() = runTest {
        val ha = RecordingHaClient()
        var refuse = true
        ha.answer = { topic, _ -> !(refuse && topic == ha.frontModeDiscoveryTopic("cam")) }
        val p = configured(ha, backgroundScope)
        p.offerFrontMode({ ha }, "cam", "Cam", null)
        runCurrent()
        refuse = false
        p.offerFrontMode({ ha }, "cam", "Cam", "LOW")
        runCurrent()
        p.offerFrontMode({ ha }, "cam", "Cam", "OFF")
        runCurrent()
        assertEquals("sent again after the refusal, then never", 2, ha.frontModeDiscoveries("cam"))
        assertEquals(listOf("LOW", "OFF"), ha.frontModeStates("cam"))
    }

    @Test fun aHungBatterySendDoesNotHoldTheSameDevicesFrontMode() = runTest {
        val ha = RecordingHaClient()
        val release = CompletableDeferred<Unit>()
        ha.answer = { topic, _ ->
            if (topic == "bikeradar/cam/battery") release.await()
            true
        }
        val p = configured(ha, backgroundScope)
        p.offerBattery("Cam", 80)
        runCurrent()
        p.offerFrontMode({ ha }, "cam", "Cam", "LOW")
        runCurrent()
        assertEquals(listOf("LOW"), ha.frontModeStates("cam"))
        release.complete(Unit)
        runCurrent()
    }

    @Test fun anUnconfiguredClientIsSentNoFrontMode() = runTest {
        val ha = RecordingHaClient(baseUrl = "")
        val p = configured(RecordingHaClient(), backgroundScope)
        p.offerFrontMode({ ha }, "cam", "Cam", "LOW")
        runCurrent()
        assertEquals(emptyList<Pair<String, String>>(), ha.sends.toList())
    }

    @Test fun aFailedOfferReportsItsCause() = runTest {
        val ha = RecordingHaClient()
        ha.failure = HaFailure.HOST_UNREACHABLE
        ha.answer = { topic, _ -> !topic.endsWith("/battery") }
        val p = configured(ha, backgroundScope)
        p.offerBattery("Radar", 80)
        runCurrent()
        val health = HaHealthBus.families.value[HaFamily.BATTERY]
        assertTrue("a failed send must reach the bus, got $health", health is HaHealth.Error)
        assertEquals(HaFailure.HOST_UNREACHABLE, (health as HaHealth.Error).cause)
    }
}
