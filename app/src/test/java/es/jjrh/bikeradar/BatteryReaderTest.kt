// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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

    /** A Prefs whose dashcam is [camAddr] (the device under test in dashcam
     *  cases). The ownership switch is part of the fixture: a pick without it
     *  is a camera the app may not touch at all, which
     *  [aDisownedDashcamIsNeverConnectedTo] covers. */
    private fun prefsWithDashcam(
        displayName: String? = null,
        ownership: DashcamOwnership = DashcamOwnership.YES,
    ) = Prefs(app).apply {
        dashcamMac = camAddr
        dashcamDisplayName = displayName
        dashcamOwnership = ownership
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
            .doReadBattery("RearVue8", radarAddr)

        assertEquals(setOf("RearVue8" to radarAddr), kd.load().toSet())
        assertEquals("rearvue8", macToSlug[radarAddr])
        assertEquals(77, BatteryStateBus.entries.value["rearvue8"]?.pct)
        assertNull("a non-dashcam read must not touch the dashcam display name", prefs.dashcamDisplayName)
        assertTrue("publish returned true, so the 5-min throttle must arm", throttleArmed("rearvue8"))
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
        kd.save(listOf("RearVue8" to "99:99:99:99:99:99"))
        reader(knownDevices = kd, readBatteryFn = { 40 }).doReadBattery("RearVue8", radarAddr)

        // The stale name->mac pair is replaced, not duplicated.
        assertEquals(setOf("RearVue8" to radarAddr), kd.load().toSet())
    }

    // ── publish-gated throttle ───────────────────────────────────────────────

    @Test
    fun publishFailureUpdatesBusButLeavesThrottleUnarmed() = runTest {
        reader(readBatteryFn = { 60 }, publishBattery = { _, _ -> false })
            .doReadBattery("RearVue8", radarAddr)

        assertEquals("the read still publishes to the bus regardless of HA", 60, BatteryStateBus.entries.value["rearvue8"]?.pct)
        assertFalse("a failed HA publish must leave the throttle unarmed for retry", throttleArmed("rearvue8"))
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
    fun aDisownedDashcamIsNeverConnectedTo() = runTest {
        // The gap the ownership gate could not close on its own: a device
        // reaches this path by its advert name, so nothing upstream consulted
        // the rider's pick. The read itself has to decline, or "switched off"
        // means "hidden from every screen while the app keeps connecting".
        val prefs = prefsWithDashcam(displayName = "Old Cam", ownership = DashcamOwnership.NO)
        var reads = 0
        val countingRead: suspend (String) -> Int? = {
            reads++
            80
        }
        reader(prefs = prefs, readBatteryFn = countingRead).doReadBattery("Cam", camAddr)

        assertEquals("the app must not connect to a camera the rider switched off", 0, reads)
        assertNull("and must publish nothing about it", BatteryStateBus.entries.value["cam"])
    }

    @Test
    fun anOwnedDashcamIsStillRead() = runTest {
        // The bound: the camera in use must still be read.
        val prefs = prefsWithDashcam(displayName = "Cam")
        var reads = 0
        val countingRead: suspend (String) -> Int? = {
            reads++
            80
        }
        reader(prefs = prefs, readBatteryFn = countingRead).doReadBattery("Cam", camAddr)

        assertEquals(1, reads)
        assertEquals(80, BatteryStateBus.entries.value["cam"]?.pct)
    }

    // ── which devices are read at all ────────────────────────────────────────
    // Radar names here have the shape real hardware advertises: a device
    // called "Radar" matches no radar predicate, so it could only be read as a
    // radar by a rule that read everything.

    private fun readsOf(prefs: Prefs, name: String, mac: String, kd: KnownDevices = knownStore()): Int {
        var reads = 0
        val countingRead: suspend (String) -> Int? = {
            reads++
            80
        }
        runBlocking {
            reader(prefs = prefs, knownDevices = kd, readBatteryFn = countingRead).doReadBattery(name, mac)
        }
        return reads
    }

    @Test
    fun aClearedCameraIsNotReadAgain() {
        // The rider switched the camera off and then cleared it. Nothing
        // remembers its address any more, so only its name can refuse it.
        val prefs = Prefs(app).apply { dashcamOwnership = DashcamOwnership.NO }
        val kd = knownStore("cleared")

        assertEquals(0, readsOf(prefs, "VUE-12345", camAddr, kd))
        assertNull(BatteryStateBus.entries.value["vue-12345"])
        assertTrue("and it must not be cached as a device to come back to", kd.load().isEmpty())
    }

    @Test
    fun aCameraTheRiderNeverChoseIsNotRead() {
        val prefs = Prefs(app) // ownership UNANSWERED, no pick

        assertEquals(0, readsOf(prefs, "VUE-12345", camAddr))
    }

    @Test
    fun aReplacedCameraIsNotRead() {
        // The rider picked a different camera. The old one is still paired.
        val prefs = prefsWithDashcam(displayName = "VUE-99999")

        assertEquals(0, readsOf(prefs, "VUE-12345", "BB:BB:BB:00:00:01"))
        assertEquals("while the one in use still is", 1, readsOf(prefs, "VUE-99999", camAddr))
    }

    @Test
    fun aRadarIsReadWhateverTheCameraAnswerIs() {
        val prefs = Prefs(app).apply { dashcamOwnership = DashcamOwnership.NO }

        assertEquals(1, readsOf(prefs, "RearVue8", radarAddr))
        assertEquals(1, readsOf(prefs, "RTL515", "CC:CC:CC:00:00:01"))
        assertEquals(1, readsOf(prefs, "Varia Radar", "DD:DD:DD:00:00:01"))
    }

    @Test
    fun aSwitchedOffCameraWithARadarLikeNameIsNotRead() {
        // The picker offers this device, because its name is not only a
        // radar's. So the radar test must not claim it either, or switching
        // it off would stop nothing.
        val prefs = prefsWithDashcam(displayName = "Garmin RearView Dash Cam", ownership = DashcamOwnership.NO)

        assertEquals(0, readsOf(prefs, "Garmin RearView Dash Cam", camAddr))
    }

    @Test
    fun aPinnedRadarIsReadEvenUnderANameNothingRecognises() {
        // "My radar isn't listed": the pin is the only thing identifying it.
        val prefs = Prefs(app).apply { radarMac = radarAddr }

        assertEquals(1, readsOf(prefs, "Bike Thing", radarAddr))
        assertEquals("the stored pin and the sighting need not agree in case", 1, readsOf(prefs, "Bike Thing", radarAddr.lowercase()))
        assertEquals("the pin names one device, not the name", 0, readsOf(prefs, "Bike Thing", "AA:AA:AA:00:00:01"))
    }

    @Test
    fun theCameraInUseIsReadUnderAnyName() {
        // The picker offers every paired device, so the pick need not look
        // like a camera.
        val prefs = prefsWithDashcam(displayName = "Some Cam").apply { dashcamMac = "AA:BB:CC:00:00:0F" }

        assertEquals(1, readsOf(prefs, "Some Cam", "AA:BB:CC:00:00:0F"))
        assertEquals("the stored pick and the sighting need not agree in case", 1, readsOf(prefs, "Some Cam", "aa:bb:cc:00:00:0f"))
    }

    @Test
    fun aDisownedDashcamLeavesEveryRecordOfItAlone() = runTest {
        // What the refusal above means downstream: no backoff bookkeeping, no
        // display-name resync, and the pick still there for when the switch
        // comes back. A CONSEQUENCE test, not a second pin: the refusal returns
        // before either of those sites, so no mutation of them can be told
        // apart from here.
        val prefs = prefsWithDashcam(displayName = "Old Cam", ownership = DashcamOwnership.NO)
        val failures = mutableMapOf<String, Int>()
        reader(prefs = prefs, dashcamProbeFailures = failures, readBatteryFn = { null })
            .doReadBattery("New Cam", camAddr)

        assertTrue("a camera the rider switched off must not drive the backoff", failures.isEmpty())
        assertEquals("nor have its name resynced", "Old Cam", prefs.dashcamDisplayName)
        assertEquals("and the remembered pick must survive the switch", camAddr, prefs.dashcamMac)
    }

    @Test
    fun readFailureOnNonDashcamDoesNotTouchBackoff() = runTest {
        val failures = mutableMapOf<String, Int>()
        var reads = 0
        val failingRead: suspend (String) -> Int? = {
            reads++
            null
        }
        reader(dashcamProbeFailures = failures, readBatteryFn = failingRead)
            .doReadBattery("RearVue8", radarAddr)

        assertEquals("a refused device would leave the counter empty too", 1, reads)
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
