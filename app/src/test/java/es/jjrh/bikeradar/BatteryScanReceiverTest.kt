// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.Manifest
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * Covers BatteryScanReceiver's full advert-broadcast handling: the
 * scan-error and match-lost short-circuits, and the match-and-forward
 * gate that decides whether an advertisement turns into an
 * ACTION_READ_DEVICE start of [BikeRadarService]. The bonded check is the
 * security-relevant branch - without it any peer spoofing the Garmin
 * company UUID plus a name matching the heuristic could trigger GATT
 * churn or slug injection - so both bonded and unbonded paths are pinned.
 *
 * Scope: this exercises the host-side gate only. The off-host hardware
 * filter on the company UUID (registered by BikeRadarService's scan) is not
 * reachable from a unit test - Robolectric delivers adverts straight to
 * onReceive - so a regression that widened that filter would not surface here.
 */
@RunWith(RobolectricTestRunner::class)
class BatteryScanReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val receiver = BatteryScanReceiver()
    private val adapter =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // The 4-arg ScanResult constructor is deprecated on the framework side,
    // but it's the only one a JVM test can build without the full extended-
    // advertising parameter set; the receiver only reads device + name.
    @Suppress("DEPRECATION")
    private fun scanResultFor(mac: String, name: String, bondState: Int): ScanResult {
        val device = adapter.getRemoteDevice(mac)
        shadowOf(device).setName(name)
        shadowOf(device).setBondState(bondState)
        return ScanResult(device, null, -50, 0L)
    }

    /**
     * A result whose ADVERT carries the name, which is the field production
     * prefers and no other fixture here supplies.
     *
     * It matters for the revoked-permission tests: with the advert name present
     * the name read never touches the device, so bond state becomes the only
     * BLUETOOTH_CONNECT-guarded read left - and on a radar, which does advertise
     * its local name, that is the read that actually throws in the field.
     *
     * `ScanRecord` has no public constructor; `parseFromBytes` is hidden API,
     * reachable here because Robolectric runs against a jar that keeps it.
     */
    @Suppress("DEPRECATION")
    private fun scanResultAdvertising(mac: String, name: String, bondState: Int): ScanResult {
        val device = adapter.getRemoteDevice(mac)
        shadowOf(device).setName("wrong - the advert name must win")
        shadowOf(device).setBondState(bondState)
        val nameBytes = name.toByteArray()
        // One AD structure: length, then type 0x09 (Complete Local Name), then the name.
        val ad = byteArrayOf((nameBytes.size + 1).toByte(), 0x09) + nameBytes
        val record = ScanRecord::class.java
            .getMethod("parseFromBytes", ByteArray::class.java)
            .invoke(null, ad) as ScanRecord
        return ScanResult(device, record, -50, 0L)
    }

    /**
     * Stands in for the OS refusing a service start. `ContextCompat`
     * routes to [Context.startForegroundService] on this minSdk, so
     * overriding it here reaches the receiver's real call site.
     */
    private class ThrowingStartContext(
        base: Context,
        private val toThrow: RuntimeException,
    ) : ContextWrapper(base) {
        var startAttempts = 0
            private set

        override fun startForegroundService(service: Intent): ComponentName? {
            startAttempts++
            throw toThrow
        }
    }

    private fun refusal() = ForegroundServiceStartNotAllowedException(
        "startForegroundService() not allowed",
    )

    /**
     * Makes this one device's name and bond-state reads throw, as the framework
     * does once BLUETOOTH_CONNECT is revoked under a still-registered scan.
     *
     * The shadow needs the per-device flag AND the app-wide denial, so an
     * unflagged device still reads - which is what lets a test cross a denied
     * read with a live one. The denial is kept explicit even though Robolectric
     * denies by default, so the fixture does not depend on that default.
     *
     * Devices are interned by address, so a denied and a live device in the
     * same test must use different MACs or the flag rides both.
     */
    private fun denyConnectOn(r: ScanResult) {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(r.device).setShouldThrowSecurityExceptions(true)
    }

    private fun journalLines(): List<String> = LinkEventJournal({ app.getExternalFilesDir(null) }).readTail()

    @Before
    @After
    fun clearJournalAndThrottle() {
        app.getExternalFilesDir(null)?.let {
            File(File(it, LinkEventJournal.JOURNAL_DIR), LinkEventJournal.FILE_NAME).delete()
        }
        BatteryScanReceiver.refusalThrottle = BatteryScanReceiver.JournalLogThrottle()
        BatteryScanReceiver.revocationThrottle = BatteryScanReceiver.JournalLogThrottle()
    }

    private fun batchIntent(callbackType: Int, vararg results: ScanResult): Intent = Intent().apply {
        putExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, callbackType)
        putParcelableArrayListExtra(
            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
            ArrayList(results.toList()),
        )
    }

    @Test
    fun scanErrorBranchReturnsWithoutForwarding() {
        val intent = Intent().apply {
            putExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 2)
        }
        receiver.onReceive(app, intent)
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun matchLostBranchHandlesEmptyList() {
        val intent = Intent().apply {
            putExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, ScanSettings.CALLBACK_TYPE_MATCH_LOST)
        }
        receiver.onReceive(app, intent)
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun matchLostWithResultsLogsButNeverForwards() {
        // The match-lost path walks its results to log departures; it must
        // never start a service even for a bonded, name-matching device.
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_MATCH_LOST, r))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun emptyResultListReturnsWithoutForwarding() {
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun bondedMatchingDeviceForwardsAReadDeviceStart() {
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        val started = shadowOf(app).peekNextStartedService()
        assertEquals(BikeRadarService.ACTION_READ_DEVICE, started.action)
        assertEquals("RearVue8", started.getStringExtra(BikeRadarService.EXTRA_NAME))
        assertEquals("AA:BB:CC:DD:EE:FF", started.getStringExtra(BikeRadarService.EXTRA_MAC))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S, Build.VERSION_CODES.S_V2])
    fun bondedMatchingDeviceForwardsAReadDeviceStartBeforeTiramisu() {
        // extractResults picks its overload on SDK_INT, and robolectric.properties
        // pins the suite to 35 - so the pre-33 arm is live on API 31-32 and runs
        // in no other test. It is the arm that reads the batch at all: if it
        // returned nothing, every wake on those two API levels would silently
        // stop reaching the service.
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        val started = shadowOf(app).peekNextStartedService()
        assertNotNull("expected the pre-33 arm to read the batch", started)
        assertEquals(BikeRadarService.ACTION_READ_DEVICE, started.action)
        assertEquals("AA:BB:CC:DD:EE:FF", started.getStringExtra(BikeRadarService.EXTRA_MAC))
    }

    @Test
    fun theAdvertisedNameIsPreferredOverTheBondRecordName() {
        // Production reads scanRecord.deviceName first and falls back to the
        // device. Every other fixture leaves the advert empty, so without this
        // the preferred source is never exercised and dropping it is invisible.
        val r = scanResultAdvertising("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        val started = shadowOf(app).peekNextStartedService()
        assertNotNull("expected the advertised name to match", started)
        assertEquals("RearVue8", started.getStringExtra(BikeRadarService.EXTRA_NAME))
    }

    @Test
    fun aDenialIsCaughtWhenOnlyTheBondReadIsGuarded() {
        // The field case, and the one every other revocation test misses: a
        // radar advertises its local name, so the name resolves without
        // touching the device and bond state is the only guarded read left.
        // Move that read out of the try and this is the test that goes red.
        val r = scanResultAdvertising("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        denyConnectOn(r)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        assertNull(shadowOf(app).peekNextStartedService())
        val lines = journalLines()
        assertEquals("expected one journal line, got $lines", 1, lines.size)
        assertTrue("expected the reason in $lines", lines[0].contains("permission revoked"))
    }

    @Test
    fun aDeviceStillBondingIsSkipped() {
        // The bond gate is the security-relevant branch and this receiver holds
        // the only bond check on its path - nothing downstream re-checks. BONDING
        // is neither of the two states the other tests use, so without this the
        // gate can be widened to "not BOND_NONE" with the suite still green.
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDING)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun unbondedMatchingDeviceIsSkipped() {
        // The defence-in-depth gate: a matching name from an unpaired device
        // must not start a GATT read.
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_NONE)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun nonMatchingNameIsSkippedEvenWhenBonded() {
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "Pixel Buds", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun pinnedMacBypassesTheNameGateWhenBonded() {
        // The "my radar isn't listed" escape hatch: a bonded device pinned as
        // this bike's radar forwards even with a name the heuristic rejects.
        val prefs = es.jjrh.bikeradar.data.Prefs(app)
        prefs.radarMac = "AA:BB:CC:DD:EE:FF"
        try {
            val r = scanResultFor("AA:BB:CC:DD:EE:FF", "Wahoo", BluetoothDevice.BOND_BONDED)
            receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
            val started = shadowOf(app).peekNextStartedService()
            assertEquals(BikeRadarService.ACTION_READ_DEVICE, started.action)
            assertEquals("AA:BB:CC:DD:EE:FF", started.getStringExtra(BikeRadarService.EXTRA_MAC))
        } finally {
            prefs.radarMac = null
        }
    }

    @Test
    fun pinnedMacDoesNotBypassTheBondGate() {
        // The pin relaxes only the NAME gate; an unbonded sighting of the
        // pinned MAC must still be rejected (defence-in-depth stands).
        val prefs = es.jjrh.bikeradar.data.Prefs(app)
        prefs.radarMac = "AA:BB:CC:DD:EE:FF"
        try {
            val r = scanResultFor("AA:BB:CC:DD:EE:FF", "Wahoo", BluetoothDevice.BOND_NONE)
            receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
            assertNull(shadowOf(app).peekNextStartedService())
        } finally {
            prefs.radarMac = null
        }
    }

    @Test
    fun refusedStartIsSwallowedAndRecordedInTheJournal() {
        // The scan PendingIntent outlives the process, so an advert can wake
        // the receiver with no service running and Android refuses the start.
        // Uncaught, that killed the process on every scan batch.
        val ctx = ThrowingStartContext(app, refusal())
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(ctx, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        assertEquals(1, ctx.startAttempts)
        val lines = journalLines()
        assertEquals("expected one journal line, got $lines", 1, lines.size)
        assertTrue("expected the device name in $lines", lines[0].contains("RearVue8"))
        assertTrue("expected the reason in $lines", lines[0].contains("app not running"))
    }

    @Test
    fun theThrottleHoldsAcrossSeparateReceiverInstances() {
        // The OS builds a fresh receiver per broadcast, which is the whole
        // reason the throttle lives on the companion. Reusing one receiver
        // cannot tell that apart from a throttle that resets per instance -
        // and a per-instance one would write a line on every single wake.
        val ctx = ThrowingStartContext(app, refusal())
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        repeat(3) {
            BatteryScanReceiver().onReceive(ctx, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        }
        assertEquals(3, ctx.startAttempts)
        assertEquals(1, journalLines().size)
    }

    @Test
    fun refusedStartAbortsTheRestOfTheBatch() {
        // Every result in the batch would be refused identically, so the
        // first refusal ends the batch rather than retrying per device.
        val ctx = ThrowingStartContext(app, refusal())
        val a = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        val b = scanResultFor("AA:BB:CC:DD:EE:00", "Varia RTL515", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(ctx, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, a, b))
        assertEquals(1, ctx.startAttempts)
        assertEquals(1, journalLines().size)
    }

    @Test
    fun theBatchAbortsOnTheFirstRefusalEvenWhenTheLineIsThrottled() {
        // The batch ends because the process cannot be started, not because
        // this refusal earned a journal line. Warm the throttle first, so the
        // batch under test is one whose line is suppressed - otherwise the
        // abort and the logging are indistinguishable.
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(
            ThrowingStartContext(app, refusal()),
            batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r),
        )
        assertEquals(1, journalLines().size)

        val ctx = ThrowingStartContext(app, refusal())
        val second = scanResultFor("AA:BB:CC:DD:EE:00", "Varia RTL515", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(ctx, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r, second))
        assertEquals(1, ctx.startAttempts)
        assertEquals(1, journalLines().size)
    }

    @Test
    fun successfulForwardWritesNoJournalLine() {
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        assertEquals(BikeRadarService.ACTION_READ_DEVICE, shadowOf(app).peekNextStartedService().action)
        assertEquals(emptyList<String>(), journalLines())
    }

    @Test
    fun anUnrelatedStartFailureStillPropagates() {
        // The catch is narrow on purpose: a genuine bug in the start path
        // must not be hidden behind the app-is-closed journal line.
        val ctx = ThrowingStartContext(app, IllegalStateException("boom"))
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        try {
            receiver.onReceive(ctx, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
            fail("expected the unrelated failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(emptyList<String>(), journalLines())
    }

    @Test
    fun repeatedRefusalsWriteOneJournalLinePerWindow() {
        // The refusal repeats for as long as the app stays closed and every
        // repeat says the same thing; unthrottled it would flush the link
        // history out of the capped journal within a single ride.
        val ctx = ThrowingStartContext(app, refusal())
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        repeat(3) {
            receiver.onReceive(ctx, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        }
        assertEquals(3, ctx.startAttempts)
        assertEquals(1, journalLines().size)
    }

    @Test
    fun theJournalThrottleOpensAgainAfterTheWindow() {
        val window = BatteryScanReceiver.JOURNAL_LOG_WINDOW_MS
        val throttle = BatteryScanReceiver.JournalLogThrottle()
        assertTrue(throttle.shouldLog(1_000L))
        assertFalse(throttle.shouldLog(1_000L + 1))
        assertFalse(throttle.shouldLog(1_000L + window - 1))
        assertTrue(throttle.shouldLog(1_000L + window))
    }

    @Test
    fun aFreshJournalThrottleLogsImmediately() {
        // Pins the starting state: a throttle that began mid-window would
        // swallow the first refusal after every process start.
        assertTrue(BatteryScanReceiver.JournalLogThrottle().shouldLog(0L))
        assertTrue(BatteryScanReceiver.JournalLogThrottle().shouldLog(1L))
    }

    @Test
    fun theRefusalWindowReopensOnTheMonotonicClock() {
        // Pins the clock the receiver reads, not just the pure gate: only
        // elapsed-realtime advances here, so a wall-clock source would leave
        // the window shut and the second line unwritten.
        val ctx = ThrowingStartContext(app, refusal())
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(ctx, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        assertEquals(1, journalLines().size)
        ShadowSystemClock.advanceBy(Duration.ofMillis(BatteryScanReceiver.JOURNAL_LOG_WINDOW_MS))
        receiver.onReceive(ctx, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        assertEquals(2, journalLines().size)
    }

    @Test
    fun theJournalLogWindowIsFifteenMinutes() {
        // The literal is what stops the window tests above being tautologies:
        // they drive the window off the constant, so all of them stay green if
        // it changes. Do not delete this as redundant.
        assertEquals(900_000L, BatteryScanReceiver.JOURNAL_LOG_WINDOW_MS)
    }

    @Test
    fun aRevokedConnectPermissionIsSwallowedAndRecordedInTheJournal() {
        // The scan outlives the GRANT as well as the process, so a wake can
        // arrive with the device read denied. Uncaught, that killed the process
        // on every batch - the same symptom as a refused service start.
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        denyConnectOn(r)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        assertNull(shadowOf(app).peekNextStartedService())
        val lines = journalLines()
        assertEquals("expected one journal line, got $lines", 1, lines.size)
        assertTrue("expected the reason in $lines", lines[0].contains("permission revoked"))
    }

    @Test
    fun aRevokedConnectPermissionIsSwallowedOnTheMatchLostPath() {
        // Match-lost reads the device name to log the departure, so it throws
        // on the same revocation and needs the same handling.
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        denyConnectOn(r)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_MATCH_LOST, r))
        assertNull(shadowOf(app).peekNextStartedService())
        val lines = journalLines()
        assertEquals("expected one journal line, got $lines", 1, lines.size)
        assertTrue("expected the reason in $lines", lines[0].contains("permission revoked"))
    }

    @Test
    fun aRevokedPermissionAbortsTheRestOfTheBatch() {
        // Deny the FIRST device only: had the batch carried on past the denial,
        // the second - bonded and name-matching - would have started the
        // service. A mixed state cannot occur on a real device, where the grant
        // is app-wide; this pins the control flow, not the physics. Counting
        // journal lines cannot pin it, because the throttle would swallow a
        // second denial too.
        val a = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        val b = scanResultFor("AA:BB:CC:DD:EE:00", "Varia RTL515", BluetoothDevice.BOND_BONDED)
        denyConnectOn(a)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, a, b))
        assertNull(shadowOf(app).peekNextStartedService())
        assertEquals(1, journalLines().size)
    }

    @Test
    fun repeatedRevocationsWriteOneJournalLinePerWindow() {
        // The OS builds a fresh receiver per broadcast, so this also pins that
        // the window survives across instances.
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        denyConnectOn(r)
        repeat(3) {
            BatteryScanReceiver().onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        }
        assertEquals(1, journalLines().size)
    }

    @Test
    fun aWarmRefusalWindowDoesNotSwallowTheRevocationLine() {
        // The two conditions say different things, so each needs its own
        // window; one throttle shared between them would hide whichever
        // arrived second.
        val ok = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(
            ThrowingStartContext(app, refusal()),
            batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, ok),
        )
        assertEquals(1, journalLines().size)
        val denied = scanResultFor("AA:BB:CC:DD:EE:00", "Varia RTL515", BluetoothDevice.BOND_BONDED)
        denyConnectOn(denied)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, denied))
        assertEquals(2, journalLines().size)
    }

    @Test
    fun aWarmRevocationWindowDoesNotSwallowTheRefusalLine() {
        val denied = scanResultFor("AA:BB:CC:DD:EE:00", "Varia RTL515", BluetoothDevice.BOND_BONDED)
        denyConnectOn(denied)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, denied))
        assertEquals(1, journalLines().size)
        val ok = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(
            ThrowingStartContext(app, refusal()),
            batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, ok),
        )
        assertEquals(2, journalLines().size)
    }

    @Test
    fun theRevocationWindowReopensOnTheMonotonicClock() {
        // Twin of `theRefusalWindowReopensOnTheMonotonicClock`: pins the clock the denial branch
        // reads, not just the pure gate. A wall-clock source would leave the
        // window shut here and the second line unwritten.
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        denyConnectOn(r)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        assertEquals(1, journalLines().size)
        ShadowSystemClock.advanceBy(Duration.ofMillis(BatteryScanReceiver.JOURNAL_LOG_WINDOW_MS))
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        assertEquals(2, journalLines().size)
    }

    @Test
    fun theBatchAbortsOnTheFirstDenialEvenWhenTheLineIsThrottled() {
        // Redundant with `aRevokedPermissionAbortsTheRestOfTheBatch` while the
        // read is per-batch; kept for a refactor that makes it per-result.
        val warm = scanResultFor("AA:BB:CC:DD:EE:11", "RearVue8", BluetoothDevice.BOND_BONDED)
        denyConnectOn(warm)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, warm))
        assertEquals(1, journalLines().size)

        val a = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        val b = scanResultFor("AA:BB:CC:DD:EE:00", "Varia RTL515", BluetoothDevice.BOND_BONDED)
        denyConnectOn(a)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, a, b))
        assertNull(shadowOf(app).peekNextStartedService())
        assertEquals(1, journalLines().size)
    }

    @Test
    fun aLaterResultInTheBatchStillReachesTheService() {
        // Without this, reading only the first result passes the whole suite:
        // every other multi-result test in this class aborts on the first one.
        val skipped = scanResultFor("AA:BB:CC:DD:EE:11", "Pixel Buds", BluetoothDevice.BOND_BONDED)
        val wanted = scanResultFor("AA:BB:CC:DD:EE:00", "Varia RTL515", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, skipped, wanted))
        val started = shadowOf(app).peekNextStartedService()
        assertNotNull("expected the second result to forward", started)
        assertEquals("AA:BB:CC:DD:EE:00", started.getStringExtra(BikeRadarService.EXTRA_MAC))
    }

    @Test
    fun aNonSecurityFailureOnTheDeviceReadStillPropagates() {
        // The mirror of `aSecurityExceptionFromTheServiceStartStillPropagates`, on the read path. The
        // catch is narrow so that a malformed batch surfaces as a crash rather
        // than being journalled to the rider as a revoked permission. A null
        // element is the cheapest way to make the read throw something that is
        // not a SecurityException.
        val intent = Intent().apply {
            putExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            @Suppress("UNCHECKED_CAST")
            putParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ArrayList(listOf<ScanResult?>(null) as List<ScanResult>),
            )
        }
        try {
            receiver.onReceive(app, intent)
            fail("expected the malformed result to propagate")
        } catch (_: NullPointerException) {
            // The read threw where the fix does not catch, which is the point.
        }
        assertEquals(emptyList<String>(), journalLines())
    }

    @Test
    fun aSecurityExceptionFromTheServiceStartStillPropagates() {
        // The device reads are what a revoked grant denies; the service start
        // is not, so a SecurityException from there is a real bug and must not
        // be filed under "Bluetooth permission revoked".
        val ctx = ThrowingStartContext(app, SecurityException("boom"))
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        try {
            receiver.onReceive(ctx, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
            fail("expected the unrelated failure to propagate")
        } catch (e: SecurityException) {
            assertEquals("boom", e.message)
        }
        assertEquals(emptyList<String>(), journalLines())
    }

    @Test
    fun matchesVariaNameAcceptsTheKnownHeuristicKeywords() {
        for (n in listOf("Varia RTL515", "Vue 49548", "RearVue8", "RTL510", "Garmin Edge")) {
            assertTrue("expected $n to match", BatteryScanReceiver.matchesVariaName(n))
        }
        // Case-insensitive.
        assertTrue(BatteryScanReceiver.matchesVariaName("rearvue8"))
    }

    @Test
    fun matchesVariaNameRejectsUnrelatedNames() {
        for (n in listOf("Pixel Buds", "AirPods", "", "Wahoo")) {
            assertFalse("expected $n to be rejected", BatteryScanReceiver.matchesVariaName(n))
        }
    }
}
