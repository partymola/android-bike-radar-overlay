// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Robolectric coverage for [BatteryReader.doReadBattery] (the post-read state
 * machine) and [BatteryReader.launch] (the in-flight guard), driven through the
 * injected read/publish function seams so no live GATT is needed. The GATT read
 * itself ([BatteryReader.readBattery]) stays out of scope - it needs a fake-GATT
 * harness like [CameraLightControllerHarnessTest].
 *
 * Field names deliberately avoid `radarMac` / `dashcamMac`: those collide with
 * the same-named [Prefs] properties inside `Prefs(app).apply { ... }`, where the
 * receiver shadows a test field and a `prop = field` line self-assigns to null.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BatteryReaderTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    // doReadBattery never touches the reader's CoroutineScope (only launch does),
    // so a never-launched scope is fine for the direct-call tests.
    private val unusedScope = CoroutineScope(EmptyCoroutineContext)

    private val radarAddr = "AA:BB:CC:DD:EE:FF"
    private val camAddr = "11:22:33:44:55:66"

    @Before
    fun clearBus() {
        BatteryStateBus.clearForTest()
    }

    @After
    fun resetBus() {
        BatteryStateBus.clearForTest()
    }

    private fun knownStore(name: String = "kd") = KnownDevices(app.getSharedPreferences(name, Context.MODE_PRIVATE))

    /** A Prefs whose dashcam is [camAddr] (the device under test in dashcam cases). */
    private fun prefsWithDashcam(displayName: String? = null) = Prefs(app).apply {
        dashcamMac = camAddr
        dashcamDisplayName = displayName
    }

    private fun reader(
        scope: CoroutineScope = unusedScope,
        prefs: Prefs = prefsWithDashcam(),
        knownDevices: KnownDevices = knownStore(),
        dashcamProbeFailures: MutableMap<String, Int> = mutableMapOf(),
        macToSlug: MutableMap<String, String> = mutableMapOf(),
        publishBattery: suspend (String, Int) -> Boolean = { _, _ -> true },
        readBatteryFn: suspend (String) -> Int? = { 100 },
    ) = BatteryReader(
        context = app,
        scope = scope,
        prefs = prefs,
        knownDevices = knownDevices,
        publishBattery = publishBattery,
        macToSlug = macToSlug,
        slug = { it.lowercase() },
        dashcamProbeFailures = dashcamProbeFailures,
        readBatteryFn = readBatteryFn,
    )

    private fun throttleArmed(slug: String): Boolean = app.getSharedPreferences(PREFS_THROTTLE, Context.MODE_PRIVATE)
        .contains("${KEY_LAST_TS}_$slug")

    // ── success path ─────────────────────────────────────────────────────────

    @Test
    fun successOnNewDeviceCachesItPublishesAndArmsThrottle() = runTest {
        val prefs = prefsWithDashcam() // radar is not the dashcam
        val kd = knownStore()
        val macToSlug = mutableMapOf<String, String>()
        reader(prefs = prefs, knownDevices = kd, macToSlug = macToSlug, readBatteryFn = { 77 })
            .doReadBattery("Radar", radarAddr)

        assertEquals(setOf("Radar" to radarAddr), kd.load().toSet())
        assertEquals("radar", macToSlug[radarAddr])
        assertEquals(77, BatteryStateBus.entries.value["radar"]?.pct)
        assertNull("a non-dashcam read must not touch the dashcam display name", prefs.dashcamDisplayName)
        assertTrue("publish returned true, so the 5-min throttle must arm", throttleArmed("radar"))
    }

    @Test
    fun successOnDashcamSyncsDisplayNameAndClearsBackoff() = runTest {
        val prefs = prefsWithDashcam(displayName = "Old Cam")
        val failures = mutableMapOf(camAddr to 3)
        reader(prefs = prefs, dashcamProbeFailures = failures, readBatteryFn = { 50 })
            .doReadBattery("New Cam", camAddr)

        assertEquals("New Cam", prefs.dashcamDisplayName)
        assertFalse("a successful read clears the dashcam probe backoff", failures.containsKey(camAddr))
        assertEquals(50, BatteryStateBus.entries.value["new cam"]?.pct)
    }

    @Test
    fun knownDeviceRemapsNameToTheNewMac() = runTest {
        val kd = knownStore()
        kd.save(listOf("Radar" to "99:99:99:99:99:99"))
        reader(knownDevices = kd, readBatteryFn = { 40 }).doReadBattery("Radar", radarAddr)

        // The stale name->mac pair is replaced, not duplicated.
        assertEquals(setOf("Radar" to radarAddr), kd.load().toSet())
    }

    // ── publish-gated throttle ───────────────────────────────────────────────

    @Test
    fun publishFailureUpdatesBusButLeavesThrottleUnarmed() = runTest {
        reader(readBatteryFn = { 60 }, publishBattery = { _, _ -> false })
            .doReadBattery("Radar", radarAddr)

        assertEquals("the read still publishes to the bus regardless of HA", 60, BatteryStateBus.entries.value["radar"]?.pct)
        assertFalse("a failed HA publish must leave the throttle unarmed for retry", throttleArmed("radar"))
    }

    // ── read-failure path ────────────────────────────────────────────────────

    @Test
    fun readFailureOnDashcamIncrementsBackoffAndSkipsPublish() = runTest {
        val failures = mutableMapOf(camAddr to 2)
        reader(dashcamProbeFailures = failures, readBatteryFn = { null })
            .doReadBattery("Cam", camAddr)

        assertEquals("consecutive dashcam read failures must accumulate", 3, failures[camAddr])
        assertNull("a failed read must not push a bus entry", BatteryStateBus.entries.value["cam"])
        assertFalse("a failed read must not arm the throttle", throttleArmed("cam"))
    }

    @Test
    fun readFailureOnNonDashcamDoesNotTouchBackoff() = runTest {
        val failures = mutableMapOf<String, Int>()
        reader(dashcamProbeFailures = failures, readBatteryFn = { null })
            .doReadBattery("Radar", radarAddr)

        assertTrue("the backoff counter is dashcam-only", failures.isEmpty())
    }

    // ── in-flight guard (launch) ─────────────────────────────────────────────

    @Test
    fun inFlightGuardSkipsConcurrentLaunchThenAllowsNextAfterCompletion() = runTest {
        val release = CompletableDeferred<Int?>()
        var calls = 0
        val r = reader(
            scope = backgroundScope,
            readBatteryFn = {
                calls++
                release.await()
            },
        )

        r.launch("Cam", camAddr)
        runCurrent() // first read enters readBatteryFn and suspends at release
        assertEquals(1, calls)

        r.launch("Cam", camAddr) // a second read while the first is in flight
        runCurrent()
        assertEquals("a launch while a read is in flight must be skipped", 1, calls)

        release.complete(55)
        runCurrent() // first read finishes and clears the in-flight marker

        r.launch("Cam", camAddr) // no longer in flight, so this proceeds
        runCurrent()
        assertEquals("a launch after the prior read completes must proceed", 2, calls)
    }
}
